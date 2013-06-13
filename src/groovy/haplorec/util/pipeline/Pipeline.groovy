package haplorec.util.pipeline

import haplorec.util.Input
import haplorec.util.Sql
import haplorec.util.dependency.DependencyGraphBuilder
import haplorec.util.dependency.Dependency

public class Pipeline {
    // table alias to SQL table name mapping
    private static def defaultTables = [
        variant                     :  'job_patient_variant',
        variantGene                 : '_job_patient_variant_gene',
        genePhenotype               : 'job_patient_gene_phenotype',
        genotype                    : 'job_patient_genotype',
        geneHaplotype               : 'job_patient_gene_haplotype',
        genotypeDrugRecommendation  : 'job_patient_genotype_drug_recommendation',
        phenotypeDrugRecommendation : 'job_patient_phenotype_drug_recommendation',
        job                         : 'job',
    ]
    private static Set<CharSequence> jobTables = new HashSet(defaultTables.grep { it.key != 'job' }.collect { it.value })
	
    private static def setDefaultKwargs(Map kwargs) {
        def setDefault = { property, defaultValue -> 
            if (kwargs[property] == null) {
                kwargs[property] = defaultValue
            }
        }
        setDefault('saveAs', 'existing')
        defaultTables.keySet().each { tableAlias ->
            setDefault(tableAlias, defaultTables[tableAlias]) 
        }
    }

	/* Keyword Arguments:
	 * 
	 * tooManyHaplotypes: a Closure of type ( GeneName: String, [HaplotypeName: String] -> void )
	 * which represents a list of haplotypes (that is, 
	 * which processes genes for which more than 2 haplotypes were seen 
	 * (i.e. our assumption that the gene has a biallelic genotype has failed).
	 * default: ignore such cases
	 */
    static def geneHaplotypeToGenotype(Map kwargs = [:], groovy.sql.Sql sql) {
        setDefaultKwargs(kwargs)
		// groupedRowsToColumns(sql, kwargs.geneHaplotype, kwargs.genotype)
		// fill job_genotype using job_gene_haplotype, by mapping groups of 2 haplotypes (i.e. biallelic genes) to single job_genotype rows
        def groupBy = ['job_id', 'patient_id', 'gene_name']
		Sql.groupedRowsToColumns(sql, kwargs.geneHaplotype, kwargs.genotype,
			groupBy, 
			['job_id':'job_id', 'patient_id':'patient_id', 'gene_name':'gene_name', 'haplotype_name':['haplotype_name1', 'haplotype_name2']],
			orderRowsBy: ['haplotype_name'],
			badGroup: (kwargs.tooManyHaplotypes == null) ? null : { group ->
                def collectColumn = { column ->
                    def value = group[0][column]
                    assert group.collect { row -> row[column] }.unique().size() == 1 : "all haplotypes belong to the same $column"
                    return value
                }
                def values = values.collect { collectColumn(it) }
                def haplotypes = group.collect { row -> row['haplotype_name'] }
				kwargs.tooManyHaplotypes(values, haplotypes)
			},
			sqlParams:kwargs.sqlParams,
			rowTableWhere:"${kwargs.geneHaplotype}.job_id = :job_id",
		)
    }

    // inputGenePhenotype = create table(gene_name, phenotype_name, index(gene_name, phenotype_name))
    static def genePhenotypeToPhenotypeDrugRecommendation(Map kwargs = [:], groovy.sql.Sql sql) {
        setDefaultKwargs(kwargs)
        return Sql.selectWhereSetContains(
            sql,
            'gene_phenotype_drug_recommendation',
            kwargs.genePhenotype,
            ['gene_name', 'phenotype_name'],
            tableAGroupBy: ['drug_recommendation_id'],
            tableBGroupBy: ['job_id', 'patient_id'],
            select: ['job_id', 'patient_id', 'drug_recommendation_id'],
            intoTable: kwargs.phenotypeDrugRecommendation,
            saveAs:kwargs.saveAs, 
            sqlParams:kwargs.sqlParams,
            tableBWhere: { t -> "${t}.job_id = :job_id" },
        )
    }

    /* TODO: 
     * - create an iterator wrapper over file to process the input file into the fields we want (ASSAY_ID == snp_id, GENOTYPE_ID == allele x 1/2, SAMPLE_ID = patient_id) 
     * - call this function with kwargs.variants = iterable over file
     * - mark heterozygous calls as ignored, and specify the reason why they are ignored
     */
    static def variantToGeneHaplotype(Map kwargs = [:], groovy.sql.Sql sql) {
        setDefaultKwargs(kwargs)
        def intersectTable = '_intersect__job_patient_variant_gene_gene_haplotype_variant'
        def columns = ['job_id', 'patient_id', 'gene_name', 'haplotype_name']
        def setContainsQuery = Sql.selectWhereSetContains(
            sql,
            kwargs.variantGene,
            'gene_haplotype_variant',
			['gene_name', 'haplotype_name', 'snp_id', 'allele'],
            tableAGroupBy: ['job_id', 'patient_id', 'physical_chromosome', 'gene_name2', 'haplotype_name2'],
            // tableBGroupBy: ['haplotype_name'],
            tableBGroupBy: [],
            intersectTable: intersectTable,
			select: ['job_id', 'patient_id', 'physical_chromosome', 'gene_name2', 'haplotype_name2'],
            saveAs: 'query', 
            sqlParams: kwargs.sqlParams,
			tableAWhere: { t -> "${t}.job_id = :job_id" },
        )
                // NOTE: this where clause for filtering out calls made using too many heterozygous 
                // snps slows things down too much; still need to figure that out.
                // where
                // s.gene_name2 not in (
                //     select gene_name
                //     from ${kwargs.variant} v
                //     join gene_haplotype_variant using (snp_id)
                //     where 
                //         zygosity     = 'het'        and 
                //         v.job_id     = :job_id      and 
                //         v.job_id     = s.job_id     and 
                //         v.patient_id = s.patient_id
                //     group by job_id, patient_id, gene_name
                //     having count(distinct snp_id) > 1
                // )
        def query = """\
            select ${columns.join(', ')} from (
                select job_id, patient_id, gene_name2 as gene_name, haplotype_name2 as haplotype_name, physical_chromosome from (
                    $setContainsQuery
                ) s 
                group by job_id, patient_id, gene_name2, physical_chromosome
                having count(*) = 1
            ) t
        """
        Sql.selectAs(sql, query, columns,
			intoTable: kwargs.geneHaplotype,
			sqlParams: kwargs.sqlParams,
            saveAs: 'existing')
        // cleanup the helper tables
        cleanup(sql, intersectTable, kwargs.sqlParams.job_id)
        cleanupTemp(sql, kwargs.variantGene)
    }

    static private def cleanup(groovy.sql.Sql sql, table, jobId) {
        sql.execute "delete from $table where job_id = :job_id".toString(), [job_id: jobId]
    }

    static private def cleanupTemp(groovy.sql.Sql sql, table) {
        sql.execute "drop table $table".toString()
    }

    static def variantToVariantGene(Map kwargs = [:], groovy.sql.Sql sql) {
        Sql.createTableFromExisting(sql, kwargs.variantGene,
            query: """\
            |select job_id, patient_id, physical_chromosome, snp_id, allele, gene_name, gene_name as gene_name2, haplotype_name, haplotype_name as haplotype_name2, zygosity
            |from ${kwargs.variant}
            |join (
            |    select distinct gene_name, haplotype_name, snp_id
            |    from gene_haplotype_variant 
            |) gene_snp
            |using (snp_id)
            |where job_id = :job_id
            """.stripMargin(),
            sqlParams:kwargs.sqlParams,
            temporary: true,
            indexColumns: [
                ['gene_name', 'haplotype_name', 'snp_id', 'allele'],
                ['job_id', 'patient_id', 'physical_chromosome', 'gene_name2', 'haplotype_name2'],
            ],
            saveAs: 'MyISAM')
    }

    static def genotypeToGenotypeDrugRecommendation(Map kwargs = [:], groovy.sql.Sql sql) {
        setDefaultKwargs(kwargs)
        return Sql.selectWhereSetContains(
            sql,
            'genotype_drug_recommendation',
            kwargs.genotype,
            ['gene_name', 'haplotype_name1', 'haplotype_name2'],
            tableAGroupBy: ['drug_recommendation_id'],
            tableBGroupBy: ['job_id', 'patient_id'],
            select: ['job_id', 'patient_id', 'drug_recommendation_id'],
            intoTable: kwargs.genotypeDrugRecommendation,
            saveAs:kwargs.saveAs, 
            sqlParams:kwargs.sqlParams,
			tableBWhere: { t -> "${t}.job_id = :job_id" },
        )
    }


	// inputGenePhenotype = create table(gene_name, phenotype_name, index(gene_name, phenotype_name))
	static def genotypeToGenePhenotype(Map kwargs = [:], groovy.sql.Sql sql) {
        setDefaultKwargs(kwargs)
		return Sql.selectAs(sql, """\
			select job_id, patient_id, gene_name, phenotype_name from ${kwargs.genotype} 
			join genotype_phenotype using (gene_name, haplotype_name1, haplotype_name2)
			where ${kwargs.genotype}.job_id = :job_id""".toString(),
			['job_id', 'patient_id', 'gene_name', 'phenotype_name'],
			saveAs:kwargs.saveAs,
			intoTable:kwargs.genePhenotype,
            sqlParams:kwargs.sqlParams,
		)
	}

	static def createVariant(Map kwargs = [:], groovy.sql.Sql sql) {
		setDefaultKwargs(kwargs)
		Sql.insert(sql, kwargs.variant, ['snp_id', 'allele'], kwargs.variants)
	}

    static def dependencyGraph(Map kwargs = [:]) {
        def tbl = tables(kwargs)

        def builder = new PipelineDependencyGraphBuilder()
        Map dependencies = [:]
        def canUpload = { d -> PipelineInput.inputTables.contains(d) }

        dependencies.genotypeDrugRecommendation = builder.dependency(id: 'genotypeDrugRecommendation', target: 'genotypeDrugRecommendation', 
        name: "Genotype Drug Recommendations",
        table: tbl.genotypeDrugRecommendation,
        fileUpload: canUpload('genotypeDrugRecommendation')) {
            dependencies.genotype = dependency(id: 'genotype', target: 'genotype', 
            name: "Genotypes",
            table: tbl.genotype,
            fileUpload: canUpload('genotype')) {
                dependencies.geneHaplotype = dependency(id: 'geneHaplotype', target: 'geneHaplotype', 
                name: "Haplotypes",
                table: tbl.geneHaplotype,
                fileUpload: canUpload('geneHaplotype')) {
                    dependencies.variant = dependency(id: 'variant', target: 'variant', 
                    name: "Variants",
                    table: tbl.variant,
                    fileUpload: canUpload('variant'))
                }
            }
        }
        dependencies.phenotypeDrugRecommendation = builder.dependency(id: 'phenotypeDrugRecommendation', target: 'phenotypeDrugRecommendation', 
        name: "Phenotype Drug Recommendations",
        table: tbl.phenotypeDrugRecommendation,
        fileUpload: canUpload('phenotypeDrugRecommendation')) {
            dependencies.genePhenotype = dependency(id: 'genePhenotype', target: 'genePhenotype', 
            name: "Phenotypes",
            table: tbl.genePhenotype,
            fileUpload: canUpload('genePhenotype')) {
                dependency(refId: 'genotype')
            }
        }
        return [tbl, dependencies]
    }

    static def tables(Map kwargs = [:]) {
        // default job_* tables
        // dependency target -> sql table
		def tbl = new LinkedHashMap(defaultTables)
        return tbl
    }

    static def pipelineJob(Map kwargs = [:], groovy.sql.Sql sql) {
		def tableKey = { defaultTable ->
			defaultTable.replaceFirst(/^job_/,  "")
						.replaceAll(/_(\w)/, { it[0][1].toUpperCase() })
		}

        def (tbl, dependencies) = dependencyGraph(kwargs)

        if (kwargs.jobId == null) {
            // Create a new job
            List sqlParamsColumns = (kwargs.sqlParams?.keySet() ?: []) as List
            def keys = Sql.sqlWithParams sql.&executeInsert, """\
                insert into job(${sqlParamsColumns.collect { ":$it" }.join(', ')}) 
                values(${(['?']*sqlParamsColumns.size()).join(', ')})""".toString(), 
                kwargs.sqlParams
                kwargs.jobId = keys[0][0]
        } else {
            // Given an existing jobId, delete all job_* rows, then rerun the pipeline
            if ((sql.rows("select count(*) as count from ${tbl.job}".toString()))[0]['count'] == 0) {
                throw new IllegalArgumentException("No such job with job_id ${kwargs.jobId}")
            }
            jobTables.each { jobTable ->
                sql.execute "delete from $jobTable where job_id = :jobId".toString(), kwargs
            }
        }

        /* Given a table alias and "raw" input (that is, in the sense that it may need to be 
         * filtered or error checked), build a SQL table from that input by inserting it with a new 
         * jobId.
         */
        def jobTableInsertColumns = defaultTables.grep { it.key != 'job' }.collect { it.key }.inject([:]) { m, alias ->
            def table = tbl[alias]
            m[alias] = Sql.tableColumns(sql, tbl[alias],
                where: "column_key != 'PRI'")
			m
        }
        def buildFromInput = { alias, rawInput ->
            def input = pipelineInput(alias, rawInput)
            def jobRowIter = new Object() {
                def each(Closure f) {
                    input.each { row ->
                        row.add(0, kwargs.jobId)
						f(row)
                    }
                }
            }
            Sql.insert(sql, tbl[alias], jobTableInsertColumns[alias], jobRowIter)
        }
        def pipelineKwargs = tbl + [
            sqlParams:[
                job_id:kwargs.jobId,
            ]
        ]

        dependencies.genotypeDrugRecommendation.rule = { ->
            genotypeToGenotypeDrugRecommendation(pipelineKwargs, sql)
        }
        dependencies.phenotypeDrugRecommendation.rule = { ->
            genePhenotypeToPhenotypeDrugRecommendation(pipelineKwargs, sql)
        }
        dependencies.genotype.rule = { ->
            /* TODO: specify a way of dealing with tooManyHaplotypes errors
            */
            geneHaplotypeToGenotype(pipelineKwargs, sql)
        }
        dependencies.geneHaplotype.rule = { ->
            variantToGeneHaplotype(pipelineKwargs, sql)
        }
        dependencies.variant.rule = { ->
            if (kwargs.containsKey('variants')) {
                buildFromInput('variant', kwargs.variants)
            }
            variantToVariantGene(pipelineKwargs, sql)
        }
        dependencies.genePhenotype.rule = { ->
            genotypeToGenePhenotype(pipelineKwargs, sql)
        }

        /* For datasets that are already provided, replace their rules with ones that insert their 
         * rows into the approriate job_* table.
         */
        dependencies.keySet().each { table ->
            if (table != 'variant') {
                def inputKey = table + 's'
                if (kwargs[inputKey] != null) {
                    def input = kwargs[inputKey]
                    dependencies[table].rule = { ->
                        buildFromInput(table, input)
                    }
                }
            }
        }

        return dependencies
    }
	
	static def drugRecommendations(Map kwargs = [:], groovy.sql.Sql sql) {
        def job = pipelineJob(kwargs, sql)
        // For datasets that are already provided, insert their rows into the approriate job_* table, and mark them as built
        Set<Dependency> built = []
        job.phenotypeDrugRecommendation.build(built)
        job.genotypeDrugRecommendation.build(built)
	}
	
    /* Return an iterator over the pipeline input
     */
    private static def pipelineInput(tableAlias, input) {
        if (input instanceof List && input.size() > 0 && input[0] instanceof BufferedReader) {
			// input is a filehandle
			def tableReader = PipelineInput.tableAliasToTableReader(tableAlias)
            return new Object() {
                def each(Closure f) {
                    input.each { inputStream ->
                        tableReader(inputStream).each { row ->
                            f(row)
                        }
                    }
                }
            }
        } else if (input instanceof Collection) {
			// input is a list of rows of data to insert
            return input
        } else if (input instanceof CharSequence) {
            // input is a filename
            def tableReader = PipelineInput.tableAliasToTableReader(tableAlias)
            return tableReader(input)
        } else {
			// assume its iterable (i.e. defines .each)
			return input
            // throw new IllegalArgumentException("Invalid input for table ${tableAlias}; expected a list of rows or a filepath but saw ${input}")
        }
    }
    
}
