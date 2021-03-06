# Hail

Hail is an open-source, scalable framework for exploring and analyzing genomic data. 

The Hail project began in Fall 2015 to empower the worldwide genetics community to [harness the flood of genomes](https://www.broadinstitute.org/blog/harnessing-flood-scaling-data-science-big-genomics-era) to discover the biology of human disease. Since then, Hail has expanded to enable analysis of large-scale datasets beyond the field of genomics. 

Here are two examples of projects powered by Hail:

- The [gnomAD team](https://macarthurlab.org/2018/10/17/gnomad-v2-1/) uses Hail as its core analysis platform. [gnomAD](http://gnomad.broadinstitute.org) is among the most comprehensive catalogues of human genetic variation in the world, and one of the largest genetic datasets. [Analysis results](http://gnomad.broadinstitute.org/downloads) are shared publicly in Hail format and have had sweeping impact on biomedical research and the clinical diagnosis of genetic disorders.
- The Neale Lab at the Broad Institute used Hail to perform QC and stratified association analysis of 4203 phenotypes at each of 13M variants in 361,194 individuals from the UK Biobank in about a day. Results and code are [here](http://www.nealelab.is/uk-biobank) and tweeted daily by the [GWASbot](https://twitter.com/SbotGwa).

For genomics applications, Hail can:

 - flexibly [import and export](https://hail.is/docs/0.2/methods/impex.html) to a variety of data and annotation formats, including [VCF](https://samtools.github.io/hts-specs/VCFv4.2.pdf), [BGEN](http://www.well.ox.ac.uk/~gav/bgen_format/bgen_format_v1.2.html) and [PLINK](https://www.cog-genomics.org/plink2/formats)
 - generate variant annotations like call rate, Hardy-Weinberg equilibrium p-value, and population-specific allele count; and import annotations in parallel through [annotation datasets](https://hail.is/docs/stable/datasets.html), [VEP](https://useast.ensembl.org/info/docs/tools/vep/index.html), and [Nirvana](https://github.com/Illumina/Nirvana/wiki)
 - generate sample annotations like mean depth, imputed sex, and TiTv ratio
 - generate new annotations from existing ones as well as genotypes, and use these to filter samples, variants, and genotypes
 - find Mendelian violations in trios, prune variants in linkage disequilibrium, analyze genetic similarity between samples, and compute sample scores and variant loadings using PCA
 - perform variant, gene-burden and eQTL association analyses using linear, logistic, Poisson, and linear mixed regression, and estimate heritability
 - lots more! Check out some of the new features in [Hail 0.2](http://discuss.hail.is/t/announcing-hail-0-2/702/1).

Hail's functionality is exposed through **[Python](https://www.python.org/)** and backed by distributed algorithms built on top of **[Apache Spark](https://spark.apache.org/docs/latest/index.html)** to efficiently analyze gigabyte-scale data on a laptop or terabyte-scale data on a cluster. 

Users can script pipelines or explore data interactively in [Jupyter notebooks](http://jupyter.org/) that combine Hail's methods, PySpark's scalable [SQL](https://spark.apache.org/docs/latest/sql-programming-guide.html) and [machine learning algorithms](https://spark.apache.org/docs/latest/ml-guide.html), and Python libraries like [pandas](http://pandas.pydata.org/), [scikit-learn](http://scikit-learn.org/stable/) and inline plotting.

To learn more, you can view our talks at [Spark Summit East 2017](https://spark-summit.org/east-2017/events/scaling-genetic-data-analysis-with-apache-spark/) and [Spark Summit West 2017](https://spark-summit.org/2017/events/scaling-genetic-data-analysis-with-apache-spark/).

### Getting Started

To get started using Hail:

- install Hail 0.2 using the instructions in [Installation](https://hail.is/docs/0.2/getting_started.html)
- follow the [Tutorials](https://hail.is/docs/0.2/tutorials-landing.html) for examples of how to use Hail
- read the [Hail Overview](https://hail.is/docs/0.2/overview.html) for a broad introduction to Hail
- check out the [Python API](https://hail.is/docs/0.2/api.html) for detailed information on the programming interface

### User Support

There are many ways to get in touch with the Hail team if you need help using Hail, or if you would like to suggest improvements or features. We also love to hear from new users about how they are using Hail.

- chat with the Hail team in our [Zulip chatroom](https://hail.zulipchat.com).
- post to the [Discussion Forum](http://discuss.hail.is) for user support and feature requests, or to share your Hail-powered science 
- please report any suspected bugs as [GitHub issues](https://github.com/hail-is/hail/issues)

Hail uses a continuous deployment approach to software development, which means we frequently add new features. We update users about changes to Hail via the Discussion Forum. We recommend creating an account on the Discussion Forum so that you can subscribe to these updates.

### Contribute

Hail is committed to open-source development. Our [Github repo](https://github.com/hail-is/hail) is publicly visible. If you'd like to contribute to the development of methods or infrastructure, please: 

- see the [For Software Developers](https://hail.is/docs/0.2/getting_started_developing.html) section of the installation guide for info on compiling Hail
- chat with us about development in our [Zulip chatroom](https://hail.zulipchat.com)
- visit the [Development Forum](http://dev.hail.is) for longer-form discussions
<!--- - read [this post]() (coming soon!) for tips on submitting a successful Pull Request to our repository --->


### Hail Team

The Hail team is embedded in the [Neale lab](https://nealelab.squarespace.com/) at the [Stanley Center for Psychiatric Research](http://www.broadinstitute.org/scientific-community/science/programs/psychiatric-disease/stanley-center-psychiatric-research/stanle) of the [Broad Institute of MIT and Harvard](http://www.broadinstitute.org) and the [Analytic and Translational Genetics Unit](https://www.atgu.mgh.harvard.edu/) of [Massachusetts General Hospital](http://www.massgeneral.org/).

Contact the Hail team at <a href="mailto:hail@broadinstitute.org"><code>hail@broadinstitute.org</code></a>.

Follow Hail on Twitter <a href="https://twitter.com/hailgenetics">@hailgenetics</a>.

### Citing Hail

If you use Hail for published work, please cite the software:

 - Hail, https://github.com/hail-is/hail

##### Acknowledgements

We would like to thank <a href="https://zulipchat.com/">Zulip</a> for supporting
open-source by providing free hosting, and YourKit, LLC for generously providing
free licenses for <a href="https://www.yourkit.com/java/profiler/">YourKit Java
Profiler</a> for open-source development.

<img src="https://www.yourkit.com/images/yklogo.png" align="right" />
