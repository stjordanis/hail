package is.hail.expr.ir

import is.hail.HailContext
import is.hail.expr.{JSONAnnotationImpex, ParserUtils}
import is.hail.expr.types.{MatrixType, TableType}
import is.hail.expr.types.virtual._
import is.hail.expr.types.physical.PType
import is.hail.rvd.RVDType
import is.hail.table.{Ascending, Descending, SortField}
import is.hail.utils.StringEscapeUtils._
import is.hail.utils._
import is.hail.variant.ReferenceGenome
import org.json4s.jackson.{JsonMethods, Serialization}

import scala.util.parsing.combinator.JavaTokenParsers
import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.parsing.input.Positional

abstract class Token extends Positional {
  def value: Any

  def getName: String
}

final case class IdentifierToken(value: String) extends Token {
  def getName: String = "identifier"
}

final case class StringToken(value: String) extends Token {
  def getName: String = "string"
}

final case class IntegerToken(value: Long) extends Token {
  def getName: String = "integer"
}

final case class FloatToken(value: Double) extends Token {
  def getName: String = "float"
}

final case class PunctuationToken(value: String) extends Token {
  def getName: String = "punctuation"
}

object IRLexer extends JavaTokenParsers {
  val token: Parser[Token] =
    identifier ^^ { id => IdentifierToken(id) } |
      float64_literal ^^ { d => FloatToken(d) } |
      int64_literal ^^ { l => IntegerToken(l) } |
      string_literal ^^ { s => StringToken(s) } |
      "[()\\[\\]{}<>,:+@=]".r ^^ { p => PunctuationToken(p) }

  val lexer: Parser[Array[Token]] = rep(positioned(token)) ^^ { l => l.toArray }

  def quotedLiteral(delim: Char, what: String): Parser[String] =
    new Parser[String] {
      def apply(in: Input): ParseResult[String] = {
        var r = in

        val source = in.source
        val offset = in.offset
        val start = handleWhiteSpace(source, offset)
        r = r.drop(start - offset)

        if (r.atEnd || r.first != delim)
          return Failure(s"consumed $what", r)
        r = r.rest

        val sb = new StringBuilder()

        val escapeChars = "\\bfnrt'\"`".toSet
        var continue = true
        while (continue) {
          if (r.atEnd)
            return Failure(s"unterminated $what", r)
          val c = r.first
          r = r.rest
          if (c == delim)
            continue = false
          else {
            sb += c
            if (c == '\\') {
              if (r.atEnd)
                return Failure(s"unterminated $what", r)
              val d = r.first
              if (!escapeChars.contains(d))
                return Failure(s"invalid escape character in $what", r)
              sb += d
              r = r.rest
            }
          }
        }
        Success(unescapeString(sb.result()), r)
      }
    }

  override def stringLiteral: Parser[String] =
    quotedLiteral('"', "string literal") | quotedLiteral('\'', "string literal")

  def backtickLiteral: Parser[String] = quotedLiteral('`', "backtick identifier")

  def identifier = backtickLiteral | ident

  def string_literal: Parser[String] = stringLiteral

  def int64_literal: Parser[Long] = wholeNumber.map(_.toLong)

  def float64_literal: Parser[Double] =
      "-inf" ^^ { _ => Double.NegativeInfinity } | // inf, neginf, and nan are parsed as identifiers
      """[+-]?\d+(\.\d+)?[eE][+-]?\d+""".r ^^ { _.toDouble } |
      """[+-]?\d*\.\d+""".r ^^ { _.toDouble }

  def parse(code: String): Array[Token] = {
    parseAll(lexer, code) match {
      case Success(result, _) => result
      case NoSuccess(msg, next) => ParserUtils.error(next.pos, msg)
    }
  }
}

case class IRParserEnvironment(
  refMap: Map[String, Type] = Map.empty,
  irMap: Map[String, BaseIR] = Map.empty
) {
  def update(newRefMap: Map[String, Type] = Map.empty, newIRMap: Map[String, BaseIR] = Map.empty): IRParserEnvironment =
    copy(refMap = refMap ++ newRefMap, irMap = irMap ++ newIRMap)

  def withRefMap(newRefMap: Map[String, Type]): IRParserEnvironment = {
    assert(refMap.isEmpty)
    copy(refMap = newRefMap)
  }

  def +(t: (String, Type)): IRParserEnvironment = copy(refMap = refMap + t, irMap)
}

object IRParser {
  def error(t: Token, msg: String): Nothing = ParserUtils.error(t.pos, msg)

  def consumeToken(it: TokenIterator): Token = {
    if (!it.hasNext)
      fatal("No more tokens to consume.")
    it.next()
  }

  def punctuation(it: TokenIterator, symbol: String): String = {
    consumeToken(it) match {
      case x: PunctuationToken if x.value == symbol => x.value
      case x: Token => error(x, s"Expected punctuation '$symbol' but found ${ x.getName } '${ x.value }'.")
    }
  }

  def identifier(it: TokenIterator): String = {
    consumeToken(it) match {
      case x: IdentifierToken => x.value
      case x: Token => error(x, s"Expected identifier but found ${ x.getName } '${ x.value }'.")
    }
  }

  def identifier(it: TokenIterator, expectedId: String): String = {
    consumeToken(it) match {
      case x: IdentifierToken if x.value == expectedId => x.value
      case x: Token => error(x, s"Expected identifier '$expectedId' but found ${ x.getName } '${ x.value }'.")
    }
  }

  def identifiers(it: TokenIterator): Array[String] = {
    punctuation(it, "(")
    val ids = repUntil(it, identifier, PunctuationToken(")"))
    punctuation(it, ")")
    ids
  }

  def boolean_literal(it: TokenIterator): Boolean = {
    consumeToken(it) match {
      case IdentifierToken("True") => true
      case IdentifierToken("False") => false
      case x: Token => error(x, s"Expected boolean but found ${ x.getName } '${ x.value }'.")
    }
  }

  def int32_literal(it: TokenIterator): Int = {
    consumeToken(it) match {
      case x: IntegerToken =>
        if (x.value >= Int.MinValue && x.value <= Int.MaxValue)
          x.value.toInt
        else
          error(x, s"Found integer '${ x.value }' that is outside the numeric range for int32.")
      case x: Token => error(x, s"Expected integer but found ${ x.getName } '${ x.value }'.")
    }
  }

  def int32_literals(it: TokenIterator): Array[Int] = {
    punctuation(it, "(")
    val ints = repUntil(it, int32_literal, PunctuationToken(")"))
    punctuation(it, ")")
    ints
  }

  def int64_literal(it: TokenIterator): Long = {
    consumeToken(it) match {
      case x: IntegerToken => x.value
      case x: Token => error(x, s"Expected integer but found ${ x.getName } '${ x.value }'.")
    }
  }

  def float32_literal(it: TokenIterator): Float = {
    consumeToken(it) match {
      case x: FloatToken =>
        if (x.value >= Float.MinValue && x.value <= Float.MaxValue)
          x.value.toFloat
        else
          error(x, s"Found float '${ x.value }' that is outside the numeric range for float32.")
      case x: IntegerToken => x.value.toFloat
      case x: IdentifierToken => x.value match {
        case "nan" => Float.NaN
        case "inf" => Float.PositiveInfinity
        case "neginf" => Float.NegativeInfinity
        case _ => error(x, s"Expected float but found ${ x.getName } '${ x.value }'.")
      }
      case x: Token => error(x, s"Expected float but found ${ x.getName } '${ x.value }'.")
    }
  }

  def float64_literal(it: TokenIterator): Double = {
    consumeToken(it) match {
      case x: FloatToken => x.value
      case x: IntegerToken => x.value.toDouble
      case x: IdentifierToken => x.value match {
        case "nan" => Double.NaN
        case "inf" => Double.PositiveInfinity
        case "neginf" => Double.NegativeInfinity
        case _ => error(x, s"Expected float but found ${ x.getName } '${ x.value }'.")
      }
      case x: Token => error(x, s"Expected float but found ${ x.getName } '${ x.value }'.")
    }
  }

  def string_literal(it: TokenIterator): String = {
    consumeToken(it) match {
      case x: StringToken => x.value
      case x: Token => error(x, s"Expected string but found ${ x.getName } '${ x.value }'.")
    }
  }

  def string_literals(it: TokenIterator): Array[String] = {
    punctuation(it, "(")
    val strings = repUntil(it, string_literal, PunctuationToken(")"))
    punctuation(it, ")")
    strings
  }

  def opt[T](it: TokenIterator, f: (TokenIterator) => T)(implicit tct: ClassTag[T]): Option[T] = {
    it.head match {
      case x: IdentifierToken if x.value == "None" =>
        consumeToken(it)
        None
      case _ =>
        Some(f(it))
    }
  }

  def repsepUntil[T](it: TokenIterator,
    f: (TokenIterator) => T,
    sep: Token,
    end: Token)(implicit tct: ClassTag[T]): Array[T] = {
    val xs = new ArrayBuilder[T]()
    while (it.hasNext && it.head != end) {
      xs += f(it)
      if (it.head == sep)
        consumeToken(it)
    }
    xs.result()
  }

  def repUntil[T](it: TokenIterator,
    f: (TokenIterator) => T,
    end: Token)(implicit tct: ClassTag[T]): Array[T] = {
    val xs = new ArrayBuilder[T]()
    while (it.hasNext && it.head != end) {
      xs += f(it)
    }
    xs.result()
  }

  def decorator(it: TokenIterator): (String, String) = {
    punctuation(it, "@")
    val name = identifier(it)
    punctuation(it, "=")
    val desc = string_literal(it)
    (name, desc)
  }

  def type_field(it: TokenIterator): (String, Type) = {
    val name = identifier(it)
    punctuation(it, ":")
    val typ = type_expr(it)
    while (it.hasNext && it.head == PunctuationToken("@")) {
      decorator(it)
    }
    (name, typ)
  }

  def type_exprs(it: TokenIterator): Array[Type] = {
    punctuation(it, "(")
    val types = repUntil(it, type_expr, PunctuationToken(")"))
    punctuation(it, ")")
    types
  }

  def type_expr(it: TokenIterator): Type = {
    val req = it.head match {
      case x: PunctuationToken if x.value == "+" =>
        consumeToken(it)
        true
      case _ => false
    }

    val typ = identifier(it) match {
      case "Interval" =>
        punctuation(it, "[")
        val pointType = type_expr(it)
        punctuation(it, "]")
        TInterval(pointType)
      case "Boolean" => TBoolean()
      case "Int32" => TInt32()
      case "Int64" => TInt64()
      case "Int" => TInt32()
      case "Float32" => TFloat32()
      case "Float64" => TFloat64()
      case "String" => TString()
      case "Locus" =>
        punctuation(it, "(")
        val id = identifier(it)
        punctuation(it, ")")
        ReferenceGenome.getReference(id).locusType
      case "Call" => TCall()
      case "Array" =>
        punctuation(it, "[")
        val elementType = type_expr(it)
        punctuation(it, "]")
        TArray(elementType)
      case "Set" =>
        punctuation(it, "[")
        val elementType = type_expr(it)
        punctuation(it, "]")
        TSet(elementType)
      case "Dict" =>
        punctuation(it, "[")
        val keyType = type_expr(it)
        punctuation(it, ",")
        val valueType = type_expr(it)
        punctuation(it, "]")
        TDict(keyType, valueType)
      case "Tuple" =>
        punctuation(it, "[")
        val types = repsepUntil(it, type_expr, PunctuationToken(","), PunctuationToken("]"))
        punctuation(it, "]")
        TTuple(types)
      case "Struct" =>
        punctuation(it, "{")
        val args = repsepUntil(it, type_field, PunctuationToken(","), PunctuationToken("}"))
        punctuation(it, "}")
        val fields = args.zipWithIndex.map { case ((id, t), i) => Field(id, t, i) }
        TStruct(fields)
    }

    typ.setRequired(req)
  }

  def keys(it: TokenIterator): Array[String] = {
    punctuation(it, "[")
    val keys = repsepUntil(it, identifier, PunctuationToken(","), PunctuationToken("]"))
    punctuation(it, "]")
    keys
  }

  def trailing_keys(it: TokenIterator): Array[String] = {
    it.head match {
      case x: PunctuationToken if x.value == "]" =>
        Array.empty[String]
      case x: PunctuationToken if x.value == "," =>
        punctuation(it, ",")
        repsepUntil(it, identifier, PunctuationToken(","), PunctuationToken("]"))
    }
  }

  def rvd_type_expr(it: TokenIterator): RVDType = {
    identifier(it) match {
      case "RVDType" | "OrderedRVDType" =>
        punctuation(it, "{")
        identifier(it, "key")
        punctuation(it, ":")
        punctuation(it, "[")
        val partitionKey = keys(it)
        val restKey = trailing_keys(it)
        punctuation(it, "]")
        punctuation(it, ",")
        identifier(it, "row")
        punctuation(it, ":")
        val rowType = coerce[TStruct](type_expr(it))
        RVDType(rowType.physicalType, partitionKey ++ restKey)
    }
  }

  def table_type_expr(it: TokenIterator): TableType = {
    identifier(it, "Table")
    punctuation(it, "{")

    identifier(it, "global")
    punctuation(it, ":")
    val globalType = coerce[TStruct](type_expr(it))
    punctuation(it, ",")

    identifier(it, "key")
    punctuation(it, ":")
    val key = opt(it, keys).getOrElse(Array.empty[String])
    punctuation(it, ",")

    identifier(it, "row")
    punctuation(it, ":")
    val rowType = coerce[TStruct](type_expr(it))
    punctuation(it, "}")
    TableType(rowType, key.toFastIndexedSeq, globalType)
  }

  def matrix_type_expr(it: TokenIterator): MatrixType = {
    identifier(it, "Matrix")
    punctuation(it, "{")

    identifier(it, "global")
    punctuation(it, ":")
    val globalType = coerce[TStruct](type_expr(it))
    punctuation(it, ",")

    identifier(it, "col_key")
    punctuation(it, ":")
    val colKey = keys(it)
    punctuation(it, ",")

    identifier(it, "col")
    punctuation(it, ":")
    val colType = coerce[TStruct](type_expr(it))
    punctuation(it, ",")

    identifier(it, "row_key")
    punctuation(it, ":")
    punctuation(it, "[")
    val rowPartitionKey = keys(it)
    val rowRestKey = trailing_keys(it)
    punctuation(it, "]")
    punctuation(it, ",")

    identifier(it, "row")
    punctuation(it, ":")
    val rowType = coerce[TStruct](type_expr(it))
    punctuation(it, ",")

    identifier(it, "entry")
    punctuation(it, ":")
    val entryType = coerce[TStruct](type_expr(it))
    punctuation(it, "}")

    MatrixType.fromParts(globalType, colKey, colType, rowPartitionKey ++ rowRestKey, rowType, entryType)
  }

  def agg_signature(it: TokenIterator): AggSignature = {
    punctuation(it, "(")
    val op = AggOp.fromString(identifier(it))
    val ctorArgs = type_exprs(it).map(t => -t)
    val initOpArgs = opt(it, type_exprs).map(_.map(t => -t))
    val seqOpArgs = type_exprs(it).map(t => -t)
    punctuation(it, ")")
    AggSignature(op, ctorArgs, initOpArgs.map(_.toFastIndexedSeq), seqOpArgs)
  }

  def ir_value(it: TokenIterator): (Type, Any) = {
    val typ = type_expr(it)
    val s = string_literal(it)
    val vJSON = JsonMethods.parse(s)
    val v = JSONAnnotationImpex.importAnnotation(vJSON, typ)
    (typ, v)
  }

  def named_value_irs(env: IRParserEnvironment)(it: TokenIterator): Array[(String, IR)] =
    repUntil(it, named_value_ir(env), PunctuationToken(")"))

  def named_value_ir(env: IRParserEnvironment)(it: TokenIterator): (String, IR) = {
    punctuation(it, "(")
    val name = identifier(it)
    val value = ir_value_expr(env)(it)
    punctuation(it, ")")
    (name, value)
  }

  def ir_value_exprs(env: IRParserEnvironment)(it: TokenIterator): Array[IR] = {
    punctuation(it, "(")
    val irs = ir_value_children(env)(it)
    punctuation(it, ")")
    irs
  }

  def ir_value_children(env: IRParserEnvironment)(it: TokenIterator): Array[IR] =
    repUntil(it, ir_value_expr(env), PunctuationToken(")"))

  def ir_value_expr(env: IRParserEnvironment)(it: TokenIterator): IR = {
    punctuation(it, "(")
    val ir = ir_value_expr_1(env)(it)
    punctuation(it, ")")
    ir
  }

  def ir_value_expr_1(env: IRParserEnvironment)(it: TokenIterator): IR = {
    identifier(it) match {
      case "I32" => I32(int32_literal(it))
      case "I64" => I64(int64_literal(it))
      case "F32" => F32(float32_literal(it))
      case "F64" => F64(float64_literal(it))
      case "Str" => Str(string_literal(it))
      case "True" => True()
      case "False" => False()
      case "Literal" =>
        val (t, v) = ir_value(it)
        Literal.coerce(t, v)
      case "Void" => Void()
      case "Cast" =>
        val typ = type_expr(it)
        val v = ir_value_expr(env)(it)
        Cast(v, typ)
      case "NA" => NA(type_expr(it))
      case "IsNA" => IsNA(ir_value_expr(env)(it))
      case "If" =>
        val cond = ir_value_expr(env)(it)
        val consq = ir_value_expr(env)(it)
        val altr = ir_value_expr(env)(it)
        If(cond, consq, altr)
      case "Let" =>
        val name = identifier(it)
        val value = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> value.typ))(it)
        Let(name, value, body)
      case "Ref" =>
        val id = identifier(it)
        Ref(id, env.refMap(id))
      case "ApplyBinaryPrimOp" =>
        val op = BinaryOp.fromString(identifier(it))
        val l = ir_value_expr(env)(it)
        val r = ir_value_expr(env)(it)
        ApplyBinaryPrimOp(op, l, r)
      case "ApplyUnaryPrimOp" =>
        val op = UnaryOp.fromString(identifier(it))
        val x = ir_value_expr(env)(it)
        ApplyUnaryPrimOp(op, x)
      case "ApplyComparisonOp" =>
        val opName = identifier(it)
        val l = ir_value_expr(env)(it)
        val r = ir_value_expr(env)(it)
        val op = ComparisonOp.fromStringAndTypes(opName, l.typ, r.typ)
        ApplyComparisonOp(op, l, r)
      case "MakeArray" =>
        val typ = opt(it, type_expr).map(_.asInstanceOf[TArray]).orNull
        val args = ir_value_children(env)(it)
        MakeArray.unify(args, typ)
      case "ArrayRef" =>
        val a = ir_value_expr(env)(it)
        val i = ir_value_expr(env)(it)
        ArrayRef(a, i)
      case "ArrayLen" => ArrayLen(ir_value_expr(env)(it))
      case "ArrayRange" =>
        val start = ir_value_expr(env)(it)
        val stop = ir_value_expr(env)(it)
        val step = ir_value_expr(env)(it)
        ArrayRange(start, stop, step)
      case "ArraySort" =>
        val onKey = boolean_literal(it)
        val a = ir_value_expr(env)(it)
        val ascending = ir_value_expr(env)(it)
        ArraySort(a, ascending, onKey)
      case "ToSet" => ToSet(ir_value_expr(env)(it))
      case "ToDict" => ToDict(ir_value_expr(env)(it))
      case "ToArray" => ToArray(ir_value_expr(env)(it))
      case "LowerBoundOnOrderedCollection" =>
        val onKey = boolean_literal(it)
        val col = ir_value_expr(env)(it)
        val elem = ir_value_expr(env)(it)
        LowerBoundOnOrderedCollection(col, elem, onKey)
      case "GroupByKey" =>
        val col = ir_value_expr(env)(it)
        GroupByKey(col)
      case "ArrayMap" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> coerce[TArray](a.typ).elementType))(it)
        ArrayMap(a, name, body)
      case "ArrayFilter" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> coerce[TArray](a.typ).elementType))(it)
        ArrayFilter(a, name, body)
      case "ArrayFlatMap" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name -> coerce[TArray](a.typ).elementType))(it)
        ArrayFlatMap(a, name, body)
      case "ArrayFold" =>
        val accumName = identifier(it)
        val valueName = identifier(it)
        val a = ir_value_expr(env)(it)
        val zero = ir_value_expr(env)(it)
        val eltType = coerce[TArray](a.typ).elementType
        val body = ir_value_expr(env.update(Map(accumName -> zero.typ, valueName -> eltType)))(it)
        ArrayFold(a, zero, accumName, valueName, body)
      case "ArrayScan" =>
        val accumName = identifier(it)
        val valueName = identifier(it)
        val a = ir_value_expr(env)(it)
        val zero = ir_value_expr(env)(it)
        val eltType = coerce[TArray](a.typ).elementType
        val body = ir_value_expr(env.update(Map(accumName -> zero.typ, valueName -> eltType)))(it)
        ArrayScan(a, zero, accumName, valueName, body)
      case "ArrayFor" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val body = ir_value_expr(env + (name, coerce[TArray](a.typ).elementType))(it)
        ArrayFor(a, name, body)
      case "AggFilter" =>
        val cond = ir_value_expr(env)(it)
        val aggIR = ir_value_expr(env)(it)
        AggFilter(cond, aggIR)
      case "AggExplode" =>
        val name = identifier(it)
        val a = ir_value_expr(env)(it)
        val aggBody = ir_value_expr(env + (name -> coerce[TArray](a.typ).elementType))(it)
        AggExplode(a, name, aggBody)
      case "AggGroupBy" =>
        val key = ir_value_expr(env)(it)
        val aggIR = ir_value_expr(env)(it)
        AggGroupBy(key, aggIR)
      case "ApplyAggOp" =>
        val aggSig = agg_signature(it)
        val ctorArgs = ir_value_exprs(env)(it)
        val initOpArgs = opt(it, ir_value_exprs(env))
        val seqOpArgs = ir_value_exprs(env)(it)
        ApplyAggOp(ctorArgs, initOpArgs.map(_.toFastIndexedSeq), seqOpArgs, aggSig)
      case "ApplyScanOp" =>
        val aggSig = agg_signature(it)
        val ctorArgs = ir_value_exprs(env)(it)
        val initOpArgs = opt(it, ir_value_exprs(env))
        val seqOpArgs = ir_value_exprs(env)(it)
        ApplyScanOp(ctorArgs, initOpArgs.map(_.toFastIndexedSeq), seqOpArgs, aggSig)
      case "InitOp" =>
        val aggSig = agg_signature(it)
        val i = ir_value_expr(env)(it)
        val args = ir_value_exprs(env)(it)
        InitOp(i, args, aggSig)
      case "SeqOp" =>
        val aggSig = agg_signature(it)
        val i = ir_value_expr(env)(it)
        val args = ir_value_exprs(env)(it)
        SeqOp(i, args, aggSig)
      case "Begin" =>
        val xs = ir_value_children(env)(it)
        Begin(xs)
      case "MakeStruct" =>
        val fields = named_value_irs(env)(it)
        MakeStruct(fields)
      case "SelectFields" =>
        val fields = identifiers(it)
        val old = ir_value_expr(env)(it)
        SelectFields(old, fields)
      case "InsertFields" =>
        val old = ir_value_expr(env)(it)
        val fields = named_value_irs(env)(it)
        InsertFields(old, fields)
      case "GetField" =>
        val name = identifier(it)
        val s = ir_value_expr(env)(it)
        GetField(s, name)
      case "MakeTuple" =>
        val args = ir_value_children(env)(it)
        MakeTuple(args)
      case "GetTupleElement" =>
        val idx = int32_literal(it)
        val tuple = ir_value_expr(env)(it)
        GetTupleElement(tuple, idx)
      case "StringSlice" =>
        val s = ir_value_expr(env)(it)
        val start = ir_value_expr(env)(it)
        val end = ir_value_expr(env)(it)
        StringSlice(s, start, end)
      case "StringLength" =>
        val s = ir_value_expr(env)(it)
        StringLength(s)
      case "In" =>
        val typ = type_expr(it)
        val idx = int32_literal(it)
        In(idx, typ)
      case "Die" =>
        val typ = type_expr(it)
        val msg = string_literal(it)
        Die(msg, typ)
      case "ApplySeeded" =>
        val function = identifier(it)
        val seed = int64_literal(it)
        val args = ir_value_children(env)(it)
        ApplySeeded(function, args, seed)
      case "ApplyIR" | "ApplySpecial" | "Apply" =>
        val function = identifier(it)
        val args = ir_value_children(env)(it)
        invoke(function, args: _*)
      case "Uniroot" =>
        val name = identifier(it)
        val function = ir_value_expr(env + (name -> TFloat64()))(it)
        val min = ir_value_expr(env)(it)
        val max = ir_value_expr(env)(it)
        Uniroot(name, function, min, max)
      case "TableCount" =>
        val child = table_ir(env)(it)
        TableCount(child)
      case "TableAggregate" =>
        val child = table_ir(env)(it)
        val query = ir_value_expr(env.update(child.typ.refMap))(it)
        TableAggregate(child, query)
      case "TableWrite" =>
        val path = string_literal(it)
        val overwrite = boolean_literal(it)
        val shuffleLocally = boolean_literal(it)
        val codecSpecJsonStr = opt(it, string_literal)
        val child = table_ir(env)(it)
        TableWrite(child, path, overwrite, shuffleLocally, codecSpecJsonStr.orNull)
      case "JavaIR" =>
        val name = identifier(it)
        env.irMap(name).asInstanceOf[IR]
    }
  }

  def table_irs(env: IRParserEnvironment)(it: TokenIterator): Array[TableIR] = {
    punctuation(it, "(")
    val tirs = table_ir_children(env)(it)
    punctuation(it, ")")
    tirs
  }

  def table_ir_children(env: IRParserEnvironment)(it: TokenIterator): Array[TableIR] =
    repUntil(it, table_ir(env), PunctuationToken(")"))

  def table_ir(env: IRParserEnvironment)(it: TokenIterator): TableIR = {
    punctuation(it, "(")
    val ir = table_ir_1(env)(it)
    punctuation(it, ")")
    ir
  }

  def table_ir_1(env: IRParserEnvironment)(it: TokenIterator): TableIR = {
    // FIXME TableImport
    identifier(it) match {
      case "TableKeyBy" =>
        val keys = identifiers(it)
        val isSorted = boolean_literal(it)
        val child = table_ir(env)(it)
        TableKeyBy(child, keys, isSorted)
      case "TableDistinct" =>
        val child = table_ir(env)(it)
        TableDistinct(child)
      case "TableFilter" =>
        val child = table_ir(env)(it)
        val pred = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        TableFilter(child, pred)
      case "TableRead" =>
        val path = string_literal(it)
        val dropRows = boolean_literal(it)
        val typ = opt(it, table_type_expr)
        TableIR.read(HailContext.get, path, dropRows, typ)
      case "MatrixColsTable" =>
        val child = matrix_ir(env)(it)
        MatrixColsTable(child)
      case "MatrixRowsTable" =>
        val child = matrix_ir(env)(it)
        MatrixRowsTable(child)
      case "MatrixEntriesTable" =>
        val child = matrix_ir(env)(it)
        MatrixEntriesTable(child)
      case "TableAggregateByKey" =>
        val child = table_ir(env)(it)
        val expr = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        TableAggregateByKey(child, expr)
      case "TableKeyByAndAggregate" =>
        val nPartitions = opt(it, int32_literal)
        val bufferSize = int32_literal(it)
        val child = table_ir(env)(it)
        val newEnv = env.withRefMap(child.typ.refMap)
        val expr = ir_value_expr(newEnv)(it)
        val newKey = ir_value_expr(newEnv)(it)
        TableKeyByAndAggregate(child, expr, newKey, nPartitions, bufferSize)
      case "TableRepartition" =>
        val n = int32_literal(it)
        val shuffle = boolean_literal(it)
        val child = table_ir(env)(it)
        TableRepartition(child, n, shuffle)
      case "TableHead" =>
        val n = int64_literal(it)
        val child = table_ir(env)(it)
        TableHead(child, n)
      case "TableJoin" =>
        val joinType = identifier(it)
        val joinKey = int32_literal(it)
        val left = table_ir(env)(it)
        val right = table_ir(env)(it)
        TableJoin(left, right, joinType, joinKey)
      case "TableLeftJoinRightDistinct" =>
        val root = identifier(it)
        val left = table_ir(env)(it)
        val right = table_ir(env)(it)
        TableLeftJoinRightDistinct(left, right, root)
      case "TableMultiWayZipJoin" =>
        val dataName = string_literal(it)
        val globalsName = string_literal(it)
        val children = table_ir_children(env)(it)
        TableMultiWayZipJoin(children, dataName, globalsName)
      case "TableParallelize" =>
        val nPartitions = opt(it, int32_literal)
        val rows = ir_value_expr(env)(it)
        TableParallelize(rows, nPartitions)
      case "TableMapRows" =>
        val child = table_ir(env)(it)
        val newRow = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        TableMapRows(child, newRow)
      case "TableMapGlobals" =>
        val child = table_ir(env)(it)
        val newRow = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        TableMapGlobals(child, newRow)
      case "TableRange" =>
        val n = int32_literal(it)
        val nPartitions = int32_literal(it)
        TableRange(n, nPartitions)
      case "TableUnion" =>
        val children = table_ir_children(env)(it)
        TableUnion(children)
      case "TableOrderBy" =>
        val ids = identifiers(it)
        val child = table_ir(env)(it)
        TableOrderBy(child, ids.map(i =>
          if (i.charAt(0) == 'A')
            SortField(i.substring(1), Ascending)
          else
            SortField(i.substring(1), Descending)))
      case "TableExplode" =>
        val field = identifier(it)
        val child = table_ir(env)(it)
        TableExplode(child, field)
      case "CastMatrixToTable" =>
        val entriesField = string_literal(it)
        val colsField = string_literal(it)
        val child = matrix_ir(env)(it)
        CastMatrixToTable(child, entriesField, colsField)
      case "TableRename" =>
        val rowK = string_literals(it)
        val rowV = string_literals(it)
        val globalK = string_literals(it)
        val globalV = string_literals(it)
        val child = table_ir(env)(it)
        TableRename(child, rowK.zip(rowV).toMap, globalK.zip(globalV).toMap)
      case "JavaTable" =>
        val name = identifier(it)
        env.irMap(name).asInstanceOf[TableIR]
    }
  }

  def matrix_ir_children(env: IRParserEnvironment)(it: TokenIterator): Array[MatrixIR] =
    repUntil(it, matrix_ir(env), PunctuationToken(")"))

  def matrix_ir(env: IRParserEnvironment)(it: TokenIterator): MatrixIR = {
    punctuation(it, "(")
    val ir = matrix_ir_1(env)(it)
    punctuation(it, ")")
    ir
  }

  def matrix_ir_1(env: IRParserEnvironment)(it: TokenIterator): MatrixIR = {
    identifier(it) match {
      case "MatrixFilterCols" =>
        val child = matrix_ir(env)(it)
        val pred = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixFilterCols(child, pred)
      case "MatrixFilterRows" =>
        val child = matrix_ir(env)(it)
        val pred = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixFilterRows(child, pred)
      case "MatrixFilterEntries" =>
        val child = matrix_ir(env)(it)
        val pred = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixFilterEntries(child, pred)
      case "MatrixMapCols" =>
        val newKey = opt(it, string_literals)
        val child = matrix_ir(env)(it)
        val newCol = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixMapCols(child, newCol, newKey.map(_.toFastIndexedSeq))
      case "MatrixKeyRowsBy" =>
        val key = identifiers(it)
        val isSorted = boolean_literal(it)
        val child = matrix_ir(env)(it)
        MatrixKeyRowsBy(child, key, isSorted)
      case "MatrixMapRows" =>
        val child = matrix_ir(env)(it)
        val newRow = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixMapRows(child, newRow)
      case "MatrixMapEntries" =>
        val child = matrix_ir(env)(it)
        val newEntry = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixMapEntries(child, newEntry)
      case "MatrixMapGlobals" =>
        val child = matrix_ir(env)(it)
        val newGlobals = ir_value_expr(env.withRefMap(child.typ.refMap))(it)
        MatrixMapGlobals(child, newGlobals)
      case "MatrixAggregateColsByKey" =>
        val child = matrix_ir(env)(it)
        val newEnv = env.withRefMap(child.typ.refMap)
        val entryExpr = ir_value_expr(newEnv)(it)
        val colExpr = ir_value_expr(newEnv)(it)
        MatrixAggregateColsByKey(child, entryExpr, colExpr)
      case "MatrixAggregateRowsByKey" =>
        val child = matrix_ir(env)(it)
        val newEnv = env.withRefMap(child.typ.refMap)
        val entryExpr = ir_value_expr(newEnv)(it)
        val rowExpr = ir_value_expr(newEnv)(it)
        MatrixAggregateRowsByKey(child, entryExpr, rowExpr)
      case "MatrixRead" =>
        val typ = opt(it, matrix_type_expr)
        val dropCols = boolean_literal(it)
        val dropRows = boolean_literal(it)
        val readerStr = string_literal(it)
        implicit val formats = MatrixReader.formats
        val reader = Serialization.read[MatrixReader](readerStr)
        MatrixRead(typ.getOrElse(reader.fullType), dropCols, dropRows, reader)
      case "TableToMatrixTable" =>
        val rowKey = string_literals(it)
        val colKey = string_literals(it)
        val rowFields = string_literals(it)
        val colFields = string_literals(it)
        val nPartitions = opt(it, int32_literal)
        val child = table_ir(env)(it)
        TableToMatrixTable(child, rowKey, colKey, rowFields, colFields, nPartitions)
      case "MatrixAnnotateRowsTable" =>
        val root = string_literal(it)
        val hasKey = boolean_literal(it)
        val child = matrix_ir(env)(it)
        val table = table_ir(env)(it)
        val key = ir_value_children(env.withRefMap(child.typ.refMap))(it)
        val keyIRs = if (hasKey) Some(key.toFastIndexedSeq) else None
        MatrixAnnotateRowsTable(child, table, root, keyIRs)
      case "MatrixAnnotateColsTable" =>
        val root = string_literal(it)
        val child = matrix_ir(env)(it)
        val table = table_ir(env)(it)
        MatrixAnnotateColsTable(child, table, root)
      case "MatrixExplodeRows" =>
        val path = identifiers(it)
        val child = matrix_ir(env)(it)
        MatrixExplodeRows(child, path)
      case "MatrixExplodeCols" =>
        val path = identifiers(it)
        val child = matrix_ir(env)(it)
        MatrixExplodeCols(child, path)
      case "MatrixChooseCols" =>
        val oldIndices = int32_literals(it)
        val child = matrix_ir(env)(it)
        MatrixChooseCols(child, oldIndices)
      case "MatrixCollectColsByKey" =>
        val child = matrix_ir(env)(it)
        MatrixCollectColsByKey(child)
      case "MatrixUnionRows" =>
        val children = matrix_ir_children(env)(it)
        MatrixUnionRows(children)
      case "MatrixDistinctByRow" =>
        val child = matrix_ir(env)(it)
        MatrixDistinctByRow(child)
      case "CastTableToMatrix" =>
        val entriesField = identifier(it)
        val colsField = identifier(it)
        val colKey = identifiers(it)
        val child = table_ir(env)(it)
        CastTableToMatrix(child, entriesField, colsField, colKey)
      case "JavaMatrix" =>
        val name = identifier(it)
        env.irMap(name).asInstanceOf[MatrixIR]
    }
  }

  def parse[T](s: String, f: (TokenIterator) => T): T = {
    val it = IRLexer.parse(s).toIterator.buffered
    f(it)
  }

  def parse_value_ir(s: String): IR = parse_value_ir(s, IRParserEnvironment())
  def parse_value_ir(s: String, refMap: java.util.HashMap[String, Type], irMap: java.util.HashMap[String, BaseIR]): IR =
    parse_value_ir(s, IRParserEnvironment(refMap.asScala.toMap, irMap.asScala.toMap))
  def parse_value_ir(s: String, env: IRParserEnvironment): IR = parse(s, ir_value_expr(env))

  def parse_table_ir(s: String): TableIR = parse_table_ir(s, IRParserEnvironment())
  def parse_table_ir(s: String, refMap: java.util.HashMap[String, Type], irMap: java.util.HashMap[String, BaseIR]): TableIR =
    parse_table_ir(s, IRParserEnvironment(refMap.asScala.toMap, irMap.asScala.toMap))
  def parse_table_ir(s: String, env: IRParserEnvironment): TableIR = parse(s, table_ir(env))

  def parse_matrix_ir(s: String): MatrixIR = parse_matrix_ir(s, IRParserEnvironment())
  def parse_matrix_ir(s: String, refMap: java.util.HashMap[String, Type], irMap: java.util.HashMap[String, BaseIR]): MatrixIR =
    parse_matrix_ir(s, IRParserEnvironment(refMap.asScala.toMap, irMap.asScala.toMap))
  def parse_matrix_ir(s: String, env: IRParserEnvironment): MatrixIR = parse(s, matrix_ir(env))

  def parseType(code: String): Type = parse(code, type_expr)

  def parsePType(code: String): PType = parse(code, type_expr).physicalType

  def parseStructType(code: String): TStruct = coerce[TStruct](parse(code, type_expr))

  def parseRVDType(code: String): RVDType = parse(code, rvd_type_expr)

  def parseTableType(code: String): TableType = parse(code, table_type_expr)

  def parseMatrixType(code: String): MatrixType = parse(code, matrix_type_expr)
}