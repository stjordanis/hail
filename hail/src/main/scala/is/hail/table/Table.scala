package is.hail.table

import is.hail.HailContext
import is.hail.annotations._
import is.hail.expr.{ir, _}
import is.hail.expr.ir._
import is.hail.expr.types._
import is.hail.expr.types.virtual._
import is.hail.io.plink.{FamFileConfig, LoadPlink}
import is.hail.rvd._
import is.hail.sparkextras.ContextRDD
import is.hail.utils._
import is.hail.variant._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import org.apache.spark.storage.StorageLevel
import org.json4s.jackson.JsonMethods

import scala.collection.JavaConverters._
import scala.language.implicitConversions

sealed abstract class SortOrder

case object Ascending extends SortOrder

case object Descending extends SortOrder

case class SortField(field: String, sortOrder: SortOrder)

abstract class AbstractTableSpec extends RelationalSpec {
  def references_rel_path: String
  def table_type: TableType
  def rowsComponent: RVDComponentSpec = getComponent[RVDComponentSpec]("rows")
  def rvdType(path: String): RVDType = {
    val rows = AbstractRVDSpec.read(HailContext.get, path + "/" + rowsComponent.rel_path)
    RVDType(rows.encodedType, rows.key)
  }
}

case class TableSpec(
  file_version: Int,
  hail_version: String,
  references_rel_path: String,
  table_type: TableType,
  components: Map[String, ComponentSpec]) extends AbstractTableSpec

object Table {
  def range(hc: HailContext, n: Int, nPartitions: Option[Int] = None): Table =
    new Table(hc, TableRange(n, nPartitions.getOrElse(hc.sc.defaultParallelism)))

  def fromDF(hc: HailContext, df: DataFrame, key: java.util.ArrayList[String]): Table = {
    fromDF(hc, df, key.asScala.toArray.toFastIndexedSeq)
  }

  def fromDF(hc: HailContext, df: DataFrame, key: IndexedSeq[String] = FastIndexedSeq()): Table = {
    val signature = SparkAnnotationImpex.importType(df.schema).asInstanceOf[TStruct]
    Table(hc, df.rdd, signature, key)
  }

  def read(hc: HailContext, path: String): Table =
    new Table(hc, TableIR.read(hc, path, dropRows = false, None))

  def importFamJSON(path: String, isQuantPheno: Boolean = false,
    delimiter: String = "\\t",
    missingValue: String = "NA"): String = {
    val ffConfig = FamFileConfig(isQuantPheno, delimiter, missingValue)
    val (data, typ) = LoadPlink.parseFam(path, ffConfig, HailContext.get.hadoopConf)
    val jv = JSONAnnotationImpex.exportAnnotation(
      Row(typ.toString, data),
      TStruct("type" -> TString(), "data" -> TArray(typ)))
    JsonMethods.compact(jv)
  }

  def apply(
    hc: HailContext,
    rdd: RDD[Row],
    signature: TStruct
  ): Table = apply(hc, rdd, signature, FastIndexedSeq(), isSorted = false)

  def apply(
    hc: HailContext,
    rdd: RDD[Row],
    signature: TStruct,
    isSorted: Boolean
  ): Table = apply(hc, rdd, signature, FastIndexedSeq(), isSorted)

  def apply(
    hc: HailContext,
    rdd: RDD[Row],
    signature: TStruct,
    key: IndexedSeq[String]
  ): Table = apply(hc, rdd, signature, key, TStruct.empty(), Annotation.empty, isSorted = false)

  def apply(
    hc: HailContext,
    rdd: RDD[Row],
    signature: TStruct,
    key: IndexedSeq[String],
    isSorted: Boolean
  ): Table = apply(hc, rdd, signature, key, TStruct.empty(), Annotation.empty, isSorted)

  def apply(
    hc: HailContext,
    rdd: RDD[Row],
    signature: TStruct,
    key: IndexedSeq[String],
    globalSignature: TStruct,
    globals: Annotation
  ): Table = apply(
    hc,
    ContextRDD.weaken[RVDContext](rdd),
    signature,
    key,
    globalSignature,
    globals,
    isSorted = false)

  def apply(
    hc: HailContext,
    rdd: RDD[Row],
    signature: TStruct,
    key: IndexedSeq[String],
    globalSignature: TStruct,
    globals: Annotation,
    isSorted: Boolean
  ): Table = apply(
    hc,
    ContextRDD.weaken[RVDContext](rdd),
    signature,
    key,
    globalSignature,
    globals,
    isSorted)

  def apply(
    hc: HailContext,
    crdd: ContextRDD[RVDContext, Row],
    signature: TStruct,
    key: IndexedSeq[String],
    isSorted: Boolean
  ): Table = apply(hc, crdd, signature, key, TStruct.empty(), Annotation.empty, isSorted)

  def apply(
    hc: HailContext,
    crdd: ContextRDD[RVDContext, Row],
    signature: TStruct,
    key: IndexedSeq[String],
    globalSignature: TStruct,
    globals: Annotation,
    isSorted: Boolean
  ): Table = {
    val crdd2 = crdd.cmapPartitions((ctx, it) => it.toRegionValueIterator(ctx.region, signature.physicalType))
    new Table(hc, TableLiteral(
      TableValue(
        TableType(signature, FastIndexedSeq(), globalSignature),
        BroadcastRow(globals.asInstanceOf[Row], globalSignature, hc.sc),
        RVD.unkeyed(signature.physicalType, crdd2)))
    ).keyBy(key, isSorted)
  }

  def sameWithinTolerance(t: Type, l: Array[Row], r: Array[Row], tolerance: Double, absolute: Boolean): Boolean = {
    val used = new Array[Boolean](r.length)
    var i = 0
    while (i < l.length) {
      val li = l(i)
      var matched = false
      var j = 0
      while (!matched && j < l.length && !used(j)) {
        matched = t.valuesSimilar(li, r(j), tolerance, absolute)
        if (matched)
          used(j) = true
        j += 1
      }
      if (!matched)
        return false
      i += 1
    }
    return true
  }

    def multiWayZipJoin(tables: java.util.ArrayList[Table], fieldName: String, globalName: String): Table = {
      val tabs = tables.asScala.toFastIndexedSeq
      new Table(
        tabs.head.hc,
        TableMultiWayZipJoin(
          tabs.map(_.tir),
          fieldName,
          globalName))
    }
}

class Table(val hc: HailContext, val tir: TableIR) {
  def this(
    hc: HailContext,
    crdd: ContextRDD[RVDContext, RegionValue],
    signature: TStruct,
    key: IndexedSeq[String] = FastIndexedSeq(),
    globalSignature: TStruct = TStruct.empty(),
    globals: Row = Row.empty
  ) = this(hc,
        TableLiteral(
          TableValue(
            TableType(signature, key, globalSignature),
            BroadcastRow(globals, globalSignature, hc.sc),
            RVD.coerce(RVDType(signature.physicalType, key), crdd)))
  )

  def typ: TableType = tir.typ

  lazy val value@TableValue(ktType, globals, rvd) = Interpret(tir, optimize = true)

  val TableType(signature, key, globalSignature) = tir.typ

  lazy val rdd: RDD[Row] = value.rdd

  if (!(fieldNames ++ globalSignature.fieldNames).areDistinct())
    fatal(s"Column names are not distinct: ${ (fieldNames ++ globalSignature.fieldNames).duplicates().mkString(", ") }")
  if (!key.areDistinct())
    fatal(s"Key names are not distinct: ${ key.duplicates().mkString(", ") }")
  if (!key.forall(fieldNames.contains(_)))
    fatal(s"Key names found that are not column names: ${ key.filterNot(fieldNames.contains(_)).mkString(", ") }")

  def fields: Array[Field] = signature.fields.toArray

  val keyFieldIdx: Array[Int] = key.toArray.map(signature.fieldIdx)

  def keyFields: Array[Field] = key.toArray.map(signature.fieldIdx).map(i => fields(i))

  val valueFieldIdx: Array[Int] =
    signature.fields.filter(f =>
      !key.contains(f.name)
    ).map(_.index).toArray

  def fieldNames: Array[String] = fields.map(_.name)

  def partitionCounts(): IndexedSeq[Long] = {
    tir.partitionCounts match {
      case Some(counts) => counts
      case None => rvd.countPerPartition()
    }
  }

  def count(): Long = ir.Interpret[Long](ir.TableCount(tir))

  def nColumns: Int = fields.length

  def nKeys: Int = key.length

  def nPartitions: Int = rvd.getNumPartitions

  def keySignature: TStruct = tir.typ.keyType

  def valueSignature: TStruct = {
    val (t, _) = signature.filterSet(key.toSet, include = false)
    t
  }

  def typeCheck() {

    if (!globalSignature.typeCheck(globals.value)) {
      fatal(
        s"""found violation of global signature
           |  Schema: ${ globalSignature.toString }
           |  Annotation: ${ globals.value }""".stripMargin)
    }

    val localSignature = signature
    rdd.foreach { a =>
      if (!localSignature.typeCheck(a))
        fatal(
          s"""found violation in row annotation
             |  Schema: ${ localSignature.toString }
             |
             |  Annotation: ${ Annotation.printAnnotation(a) }""".stripMargin
        )
    }
  }

  def keyedRDD(): RDD[(Row, Row)] = {
    val fieldIndices = fields.map(f => f.name -> f.index).toMap
    val keyIndices = key.map(fieldIndices)
    val keyIndexSet = keyIndices.toSet
    val valueIndices = fields.filter(f => !keyIndexSet.contains(f.index)).map(_.index)
    rdd.map { r => (Row.fromSeq(keyIndices.map(r.get)), Row.fromSeq(valueIndices.map(r.get))) }
  }

  def same(other: Table, tolerance: Double = defaultTolerance, absolute: Boolean = false): Boolean = {
    val localValueSignature = valueSignature

    val globalSignatureOpt = globalSignature.deepOptional()
    if (signature.deepOptional() != other.signature.deepOptional()) {
      info(
        s"""different signatures:
           | left: ${ signature.toString }
           | right: ${ other.signature.toString }
           |""".stripMargin)
      false
    } else if (key != other.key) {
      info(
        s"""different keys:
            | left: ${ key.map(_.mkString(", ")) }
            | right: ${ other.key.map(_.mkString(", "))}
            |""".stripMargin)
      false
    } else if (globalSignatureOpt != other.globalSignature.deepOptional()) {
      info(
        s"""different global signatures:
           | left: ${ globalSignature.toString }
           | right: ${ other.globalSignature.toString }
           |""".stripMargin)
      false
    } else if (!globalSignatureOpt.valuesSimilar(globals.value, other.globals.value)) {
      info(
        s"""different global annotations:
           | left: ${ globals.value }
           | right: ${ other.globals.value }
           |""".stripMargin)
      false
    } else if (key.nonEmpty) {
      keyedRDD().groupByKey().fullOuterJoin(other.keyedRDD().groupByKey()).forall { case (k, (v1, v2)) =>
        (v1, v2) match {
          case (Some(x), Some(y)) =>
            val r1 = x.toArray
            val r2 = y.toArray
            val res = if (r1.length != r2.length)
              false
            else r1.counter() == r2.counter() ||
              Table.sameWithinTolerance(localValueSignature, r1, r2, tolerance, absolute)
            if (!res)
              info(s"SAME KEY, DIFFERENT VALUES: k=$k\n  left:\n    ${ r1.mkString("\n    ") }\n  right:\n    ${ r2.mkString("\n    ") }")
            res
          case _ =>
            info(s"KEY MISMATCH: k=$k\n  left=$v1\n  right=$v2")
            false
        }
      }
    } else {
      assert(key.isEmpty)
      if (signature.fields.isEmpty) {
        val leftCount = count()
        val rightCount = other.count()
        if (leftCount != rightCount)
          info(s"EMPTY SCHEMAS, BUT DIFFERENT LENGTHS: left=$leftCount\n  right=$rightCount")
        leftCount == rightCount
      } else {
        keyBy(signature.fieldNames).keyedRDD().groupByKey().fullOuterJoin(
          other.keyBy(other.signature.fieldNames).keyedRDD().groupByKey()
        ).forall { case (k, (v1, v2)) =>
          (v1, v2) match {
            case (Some(x), Some(y)) => x.size == y.size
            case (Some(x), None) =>
              info(s"ROW IN LEFT, NOT RIGHT: ${ x.mkString("\n    ") }\n")
              false
            case (None, Some(y)) =>
              info(s"ROW IN RIGHT, NOT LEFT: ${ y.mkString("\n    ") }\n")
              false
            case (None, None) =>
              assert(false)
              false
          }
        }
      }
    }
  }

  def aggregateJSON(expr: String): String = {
    val (value, t) = aggregate(expr)
    makeJSON(t, value)
  }

  def aggregate(expr: String): (Any, Type) =
    aggregate(IRParser.parse_value_ir(expr, IRParserEnvironment(typ.refMap)))

  def aggregate(query: IR): (Any, Type) = {
    val t = ir.TableAggregate(tir, query)
    (ir.Interpret(t, ir.Env.empty, FastIndexedSeq(), None), t.typ)
  }

  def annotateGlobal(a: Annotation, t: Type, name: String): Table = {
    new Table(hc, TableMapGlobals(tir,
      ir.InsertFields(ir.Ref("global", tir.typ.globalType), FastSeq(name -> ir.Literal.coerce(t, a)))))
  }

  def annotateGlobal(a: Annotation, t: String, name: String): Table =
    annotateGlobal(a, IRParser.parseType(t), name)

  def selectGlobal(expr: String): Table = {
    val ir = IRParser.parse_value_ir(expr, IRParserEnvironment(typ.refMap))
    new Table(hc, TableMapGlobals(tir, ir))
  }

  def head(n: Long): Table = new Table(hc, TableHead(tir, n))

  def keyBy(keys: java.util.ArrayList[String]): Table =
    keyBy(keys.asScala.toFastIndexedSeq)

  def keyBy(keys: java.util.ArrayList[String], isSorted: Boolean): Table =
    keyBy(keys.asScala.toFastIndexedSeq, isSorted)

  def keyBy(keys: IndexedSeq[String], isSorted: Boolean = false): Table =
    new Table(hc, TableKeyBy(tir, keys, isSorted))

  def keyBy(maybeKeys: Option[IndexedSeq[String]]): Table = keyBy(maybeKeys, false)

  def keyBy(maybeKeys: Option[IndexedSeq[String]], isSorted: Boolean): Table =
    keyBy(maybeKeys.getOrElse(FastIndexedSeq()), isSorted)

  def unkey(): Table = keyBy(FastIndexedSeq())

  def mapRows(expr: String): Table =
    mapRows(IRParser.parse_value_ir(expr, IRParserEnvironment(typ.refMap)))

  def mapRows(newRow: IR): Table =
    new Table(hc, TableMapRows(tir, newRow))

  def leftJoinRightDistinct(other: Table, root: String): Table =
    new Table(hc, TableLeftJoinRightDistinct(tir, other.tir, root))

  def export(path: String, typesFile: String = null, header: Boolean = true, exportType: Int = ExportType.CONCATENATED) {
    ir.Interpret(ir.TableExport(tir, path, typesFile, header, exportType))
  }

  def distinctByKey(): Table = {
    new Table(hc, ir.TableDistinct(tir))
  }

  def toMatrixTable(
    rowKeys: Array[String],
    colKeys: Array[String],
    rowFields: Array[String],
    colFields: Array[String],
    nPartitions: Option[Int] = None
  ): MatrixTable = {
    new MatrixTable(hc, TableToMatrixTable(tir, rowKeys, colKeys, rowFields, colFields, nPartitions))
  }

  def unlocalizeEntries(
    entriesFieldName: String,
    colsFieldName: String,
    colKey: java.util.ArrayList[String]
  ): MatrixTable =
    new MatrixTable(hc,
      CastTableToMatrix(tir, entriesFieldName, colsFieldName, colKey.asScala.toFastIndexedSeq))

  def aggregateByKey(expr: String): Table = {
    val x = IRParser.parse_value_ir(expr, IRParserEnvironment(typ.refMap))
    new Table(hc, TableAggregateByKey(tir, x))
  }
  
  // expandTypes must be called before toDF
  def toDF(sqlContext: SQLContext): DataFrame = {
    val localSignature = signature.physicalType
    sqlContext.createDataFrame(
      rvd.map { rv => SafeRow(localSignature, rv) },
      signature.schema.asInstanceOf[StructType])
  }

  def explode(column: String): Table = new Table(hc, TableExplode(tir, Array(column)))

  def explode(columnNames: Array[String]): Table = {
    columnNames.foldLeft(this)((kt, name) => kt.explode(name))
  }

  def explode(columnNames: java.util.ArrayList[String]): Table = explode(columnNames.asScala.toArray)

  def collect(): Array[Row] = rdd.collect()

  def collectJSON(): String = {
    val r = JSONAnnotationImpex.exportAnnotation(collect().toFastIndexedSeq, TArray(signature))
    JsonMethods.compact(r)
  }


  def write(path: String, overwrite: Boolean = false, stageLocally: Boolean = false, codecSpecJSONStr: String = null) {
    ir.Interpret(ir.TableWrite(tir, path, overwrite, stageLocally, codecSpecJSONStr))
  }

  def cache(): Table = persist("MEMORY_ONLY")

  def persist(storageLevel: String): Table = {
    val level = try {
      StorageLevel.fromString(storageLevel)
    } catch {
      case e: IllegalArgumentException =>
        fatal(s"unknown StorageLevel `$storageLevel'")
    }

    copy2(rvd = rvd.persist(level))
  }

  def unpersist(): Table = copy2(rvd = rvd.unpersist())

  def copy2(rvd: RVD = rvd,
    signature: TStruct = signature,
    key: IndexedSeq[String] = key,
    globalSignature: TStruct = globalSignature,
    globals: BroadcastRow = globals): Table = {
    new Table(hc, TableLiteral(
      TableValue(TableType(signature, key, globalSignature), globals, rvd)
    ))
  }
}
