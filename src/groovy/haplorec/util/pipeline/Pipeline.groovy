package haplorec.util.pipeline

import haplorec.util.Input
import haplorec.util.Sql
import haplorec.util.dependency.DependencyGraphBuilder
import haplorec.util.dependency.Dependency
import haplorec.util.data.GeneHaplotypeMatrix

public class Pipeline {
    // table alias to SQL table name mapping
    private static def defaultTables = [
        variant                     : 'job_patient_variant',
        hetVariant                  : 'job_patient_het_variant',
        variantGene                 : '_job_patient_variant_gene',
        variantGeneView             : '_job_patient_variant_gene_view',
        variantGeneHaplotype        : '_job_patient_variant_gene_haplotype',
        variantGeneHaplotypeView    : '_job_patient_variant_gene_haplotype_view',
        genePhenotype               : 'job_patient_gene_phenotype',
        genotype                    : 'job_patient_genotype',
        geneHaplotype               : 'job_patient_gene_haplotype',
        novelHaplotype              : 'job_patient_novel_haplotype',
        genotypeDrugRecommendation  : 'job_patient_genotype_drug_recommendation',
        phenotypeDrugRecommendation : 'job_patient_phenotype_drug_recommendation',
        job                         : 'job',
    ]
    // tables that are generated by the pipeline
    private static def jobTables = defaultTables.grep {
        it.value.startsWith('job_patient_')
    }.inject([:]) { m, entry -> 
        m[entry.key] = entry.value
        m 
    }
	
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

    static def variantToGeneHaplotype(Map kwargs = [:], groovy.sql.Sql sql) {
        setDefaultKwargs(kwargs)
        // Create the variantGeneHaplotype table.
        variantToVariantGeneHaplotype(kwargs, sql)
        def columns = ['job_id', 'patient_id', 'gene_name', 'haplotype_name', 'physical_chromosome']
        def setContainsQuery = Sql.selectWhereSetContains(
            sql,
            kwargs.variantGeneHaplotypeView,
            'gene_haplotype_variant',
			['gene_name', 'haplotype_name', 'snp_id', 'allele'],
            tableAGroupBy: ['job_id', 'patient_id', 'physical_chromosome', 'gene_name2', 'haplotype_name2'],
            tableBGroupBy: [],
			select: ['job_id', 'patient_id', 'physical_chromosome', 'gene_name2', 'haplotype_name2'],
            saveAs: 'query', 
            sqlParams: kwargs.sqlParams,
			tableAWhere: { t -> "${t}.job_id = :job_id" },
        )
        def query = """\
            |select job_id, patient_id, gene_name2 as gene_name, haplotype_name2 as haplotype_name, physical_chromosome from (
            |    $setContainsQuery
            |) s 
            |where
            |s.gene_name2 not in (
            |    select gene_name
            |    from job_patient_variant v
            |    join gene_haplotype_variant using (snp_id)
            |    where 
            |    v.zygosity = 'het' and
            |    v.job_id = :job_id and
            |    v.patient_id = s.patient_id and
            |    v.physical_chromosome = s.physical_chromosome and
            |    gene_haplotype_variant.haplotype_name = s.haplotype_name2
            |    group by gene_name
            |    having count(*) > 1
            |)
            |group by job_id, patient_id, gene_name2, physical_chromosome
            |having count(*) = 1
        """.stripMargin()
        Sql.selectAs(sql, query, columns,
			intoTable: kwargs.geneHaplotype,
			sqlParams: kwargs.sqlParams,
            saveAs: 'existing')
        // cleanup the helper tables
        cleanup(sql, kwargs.variantGeneHaplotype, kwargs.sqlParams.job_id)
    }
    
    private static String _eq(sqlParams) {
        sqlParams.collect { param -> "${param.key} = :${param.key}" }.join(" and ") 
    }

    static def variantToGeneHaplotypeRedo(Map kwargs = [:], groovy.sql.Sql sql) {
        setDefaultKwargs(kwargs)
        def distinctGeneAndPatientSql = { variantsTable -> """\
            |select distinct gene_name, patient_id
            |from ${variantsTable}
            |join gene_haplotype_variant using (snp_id)
            |where job_id = :job_id
            |""".stripMargin()
        }
        // GeneName -> { PatientID }
        def geneToPatientId = Sql.selectAs(sql, """\
            |${distinctGeneAndPatientSql(kwargs.variant)}
            |union distinct
            |${distinctGeneAndPatientSql(kwargs.hetVariant)}
            |""".stripMargin(), null,
            sqlParams: kwargs.sqlParams,
            saveAs: 'rows')
            .inject([:]) { m, row ->
                m.get(row.gene_name, [] as Set).add(row.patient_id)
                return m
            }
        geneToPatientId.keySet().each { geneName ->
            def unambiguousHaplotypes = []
            def novelHaplotypes = []
            GeneHaplotypeMatrix ghm = GeneHaplotypeMatrix.haplotypeMatrix(sql, geneName)
            geneToPatientId[geneName].each { patientId ->
                def sqlParams = kwargs.sqlParams + [gene_name: geneName, patient_id: patientId]
                // physical_chromosome -> [homozygous variants]
                def homVars = Sql.selectAs(sql, """\
                    |select *
                    |from ${kwargs.variant}
                    |join gene_snp using (snp_id)
                    |where zygosity = 'hom' and ${_eq(sqlParams)}
                    |order by physical_chromosome
                    |""".stripMargin(),
                    sqlParams: sqlParams, null,
                    saveAs: 'rows')
                    .groupBy { it.physical_chromosome }
                // physical_chromosome -> ( het_combo -> [heterozygous variants] )
                def hetVariantCombos = Sql.selectAs(sql, """\
                    |select *
                    |from ${kwargs.hetVariant}
                    |join gene_snp using (snp_id)
                    |where ${_eq(sqlParams)}
                    |order by het_combo, physical_chromosome
                    |""".stripMargin(), null,
                    sqlParams: sqlParams,
                    saveAs: 'rows')
                    .inject([:]) { m, row ->
                        m.get(m[row.physical_chromosome], [:])
                         .get(m[row.het_combo], [])
                         .add(row)
                        return m
                    }
                /* TODO: what if there are not hets? what het_combo do we use?
                 */
                homVars.each { physicalChromosome, homVariants ->
                    (
                        hetVariantCombos.physical_chromosome ?: 
                        /* There are only homozygous variants; no heterozygous variants.  
                         * Just use het_combo == null and an empty list for hetVariants.
                         */
                        [ ( null ) : [] ]
                    ).each { hetCombo, hetVariants -> 
                        def hetCombos = hetVariantCombos.physical_chromosome != null ? hetVariants[0].het_combos : null
                        /* Find the haplotypes on a particular physical chromosome of a particular 
                         * heterozygous combination.
                         */
                        Set haplotypes = ghm.variantsToHaplotypes(homVariants + hetVariants)
                        if (haplotypes.size() == 1) {
                            /* An unambiguous, known haplotype.
                             */
                            unambiguousHaplotypes.add([
                                job_id: sqlParams.job_id,
                                patient_id: patientId,
                                physical_chromosome: physicalChromosome,
                                het_combo: hetCombo,
                                het_combos: hetCombos,
                                gene_name: geneName,
                                haplotype_name: haplotypes.iterator().next(),
                            ])
                        } else if (haplotypes.size() == 0) {
                            /* A novel haplotype.
                             */
                            novelHaplotypes.add([
                                job_id: sqlParams.job_id,
                                patient_id: patientId,
                                physical_chromosome: physicalChromosome,
                                het_combo: hetCombo,
                                het_combos: hetCombos,
                                gene_name: geneName,
                            ])
                        } else {
                            /* haplotypes.size() > 0; variants ambiguously identify many haplotypes. 
                             * Default behaviour (for now) is to ignore these.
                             */
                        }
                    }
                }
            }
            Sql.insert(sql, kwargs.geneHaplotype, null, unambiguousHaplotypes)
            Sql.insert(sql, kwargs.novelHaplotype, null, novelHaplotypes)
        }
    }

    /* Run Algorithm.disambiguateHets to populate kwargs.hetVariant, using kwargs.variant as a source of 
     * variants.
     */
    static def variantToHetVariant(Map kwargs = [:], groovy.sql.Sql sql) {
        /* Pseudocode:
         *
         * for each g in "genes with at least one snp_id in this patient's hetVariant snp_ids":
         *     ghm = GeneHaplotypeMatrix(g)
         *     for each patient in "patients in this job with at least one hetVariant":
         *         hets = "het variants for this patient belonging to snp_ids for this gene"
         *         for (het_combo_type, combos) in disambiguateHets(ghm, hets):
         *             # het_combo_type is one of:
         *             # AKnownBKnown => hets make up 2 known haplotypes for this gene
         *             # AKnownBNovel => hets make up 1 known haplotype and 1 novel haplotype for this gene
         *             het_combo = 1
         *             het_combos = combos.size()
         *             for hets in combos:
         *                 for h in hets:
         *                     insert into table.hetVariant 
         *                         h.snp_id, h.allele, h.physical_chromosome, het_combo, het_combos
         *                     het_combo += 1
         */
        GeneHaplotypeMatrix ghm
        eachGeneWithPatients(sql,
            genePatientTuples: Sql.selectAs(sql, """\
                |select distinct gene_name, patient_id
                |from ${kwargs.variant}
                |join gene_haplotype_variant using (snp_id)
                |where job_id = :job_id
                |""".stripMargin(), null,
                sqlParams: kwargs.sqlParams,
                saveAs: 'rows'),
            eachGene: { geneName ->
                ghm = GeneHaplotypeMatrix.haplotypeMatrix(sql, geneName)
            },
            eachPatient: { geneName, patientId ->
                def sqlParams = kwargs.sqlParams + [gene_name: geneName, patient_id: patientId]
                def combos = disambiguateHets(
                    ghm, 
                    Sql.selectAs(sql, """\
                        |select snp_id, allele
                        |from ${kwargs.variant}
                        |join gene_snp using (snp_id)
                        |where zygosity = 'het' and ${_eq(sqlParams)}
                        |""".stripMargin(), null,
                        sqlParams: sqlParams,
                        saveAs: 'rows')
                )
                def columns
                combos.each { hetComboType, comboOfHets ->
                    /* Add het_combo and het_combos to each heterzygote variant in (for each 
                     * possible combinations).
                     */
                    int hetCombos = comboOfHets.size()
                    comboOfHets.eachWithIndex { hets, i -> 
                        hets.each { h ->
                            h.het_combo = i + 1 
                            h.het_combos = hetCombos
                        }
                        if (columns == null) {
                            columns = h.keySet()
                        }
                    }
                }
                Sql.insert(sql, kwargs.hetVariant, columns, flatten(flatten(combos.values())))
            },
            sqlParams: kwargs.sqlParams,
        )
    }

    private static def eachGeneWithPatients(Map kwargs = [:], groovy.sql.Sql sql) {
        // GeneName -> { PatientID }
        def geneToPatientId = kwargs.genePatientTuples.inject([:]) { m, row ->
            m.get(row.gene_name, [] as Set).add(row.patient_id)
            return m
        }
        geneToPatientId.keySet().each { geneName ->
            kwargs.eachGene(geneName)
            geneToPatientId[geneName].each { patientId ->
                kwargs.eachPatient(geneName, patientId)
            }
        }
    }

    /* Return an iterator that flattens it's iterables by 1 level. 
     * Examples:
     *
     * flatten([[1, 2], [3, 4]])                                    == [1, 2, 3, 4]
     * flatten(        [ [ [1, 2], [3, 4] ], [ [5, 6], [7, 8] ] ])  == [ [1, 2], [3, 4], [5, 6], [7, 8] ]
     * flatten(flatten([ [ [1, 2], [3, 4] ], [ [5, 6], [7, 8] ] ])) == [ 1, 2, 3, 4, 5, 6, 7, 8 ]
     */
    static def flatten(iterables) {
        return new Object() {
            def each(Closure f) {
                iterables.each { iter ->
                    iter.each(f)
                }
            }
        }
    }

    static private def cleanup(groovy.sql.Sql sql, table, jobId) {
        sql.execute "delete from $table where job_id = :job_id".toString(), [job_id: jobId]
    }

    static def variantToVariantGeneHaplotype(Map kwargs = [:], groovy.sql.Sql sql) {
        def columns = ['job_id', 'patient_id', 'physical_chromosome', 'snp_id', 'allele', 'gene_name', 'haplotype_name', 'zygosity']
        Sql.selectAs(sql, """\
            |select ${columns.join(', ')}
            |from ${kwargs.variant}
            |join (
            |    select distinct gene_name, haplotype_name, snp_id
            |    from gene_haplotype_variant 
            |) gene_snp
            |using (snp_id)
            |where job_id = :job_id
            |""".stripMargin(), columns,
			intoTable: kwargs.variantGeneHaplotype,
			sqlParams: kwargs.sqlParams,
            saveAs: 'existing')
    }

    static def variantToVariantGene(Map kwargs = [:], groovy.sql.Sql sql) {
        def columns = ['job_id', 'patient_id', 'physical_chromosome', 'snp_id', 'allele', 'gene_name', 'zygosity']
        Sql.selectAs(sql, """\
            |select ${columns.join(', ')}
            |from ${kwargs.variant}
            |join gene_snp
            |using (snp_id)
            |where job_id = :job_id
            |""".stripMargin(), columns,
			intoTable: kwargs.variantGene,
			sqlParams: kwargs.sqlParams,
            saveAs: 'existing')
    }

    static def geneHaplotypeToNovelHaplotype(Map kwargs = [:], groovy.sql.Sql sql) {
        setDefaultKwargs(kwargs)
        variantToVariantGene(kwargs, sql)
        def columns = ['job_id', 'patient_id', 'gene_name', 'physical_chromosome']
        def setContainsQuery = Sql.selectWhereSetContains(
            sql,
            kwargs.variantGeneView,
            'gene_haplotype_variant',
			['gene_name', 'snp_id', 'allele'],
            tableAGroupBy: ['job_id', 'patient_id', 'physical_chromosome', 'gene_name2'],
            tableBGroupBy: ['haplotype_name'],
			select: ['job_id', 'patient_id', 'physical_chromosome', 'gene_name2', 'haplotype_name'],
            saveAs: 'query', 
            sqlParams: kwargs.sqlParams,
			tableAWhere: { t -> "${t}.job_id = :job_id" },
        )
        def query = """\
            |select ${columns.join(', ')}
            |from ${kwargs.variant} v
            |join gene_snp gs using (snp_id)
            |where
            |job_id = :job_id and
            |physical_chromosome is not null and
            |gene_name not in (
            |    select gene_name
            |    from ${kwargs.variant}
            |    join gene_snp using (snp_id)
            |    where 
            |    zygosity = 'het' and
            |    job_id = :job_id and
            |    patient_id = v.patient_id and
            |    physical_chromosome = v.physical_chromosome and
            |    gene_name = gs.gene_name
            |    group by gene_name
            |    having count(*) > 1
            |) and not exists (
            |    select *
            |    from gene_haplotype
            |    join (
            |        select job_id, patient_id, physical_chromosome, gene_name2 as gene_name, haplotype_name from (
            |            $setContainsQuery
            |        ) t
            |    ) s using (gene_name, haplotype_name)
            |    where 
            |    job_id = :job_id and
            |    patient_id = v.patient_id and
            |    physical_chromosome = v.physical_chromosome and
            |    gene_name = gs.gene_name
            |)
            |group by ${columns.join(', ')}
        """.stripMargin()


            // |) and exists (
            // |    select gene_name, snp_id
            // |    from ${kwargs.variantGene} vg
            // |    where 
            // |    job_id = :job_id and
            // |    patient_id = v.patient_id and
            // |    physical_chromosome = v.physical_chromosome and
            // |    gene_name = gs.gene_name and
            // |    not exists (
            // |        select *
            // |        from gene_haplotype_variant
            // |        where
            // |        gene_name = vg.gene_name and
            // |        snp_id = vg.snp_id and
            // |        allele = vg.allele
            // |    )
            // |)


                // -- a (gene, snp) whose allele doesn't occur in gene_hap_var
            // |) and gene_name not in (
            // |    select gene_name
            // |    from ${kwargs.geneHaplotype}
            // |    where
            // |    job_id = :job_id and
            // |    patient_id = v.patient_id and
            // |    physical_chromosome = v.physical_chromosome
            // |)
            // |group by ${columns.join(', ')}
            // |having count(*) = 1

        Sql.selectAs(sql, query, columns,
			intoTable: kwargs.novelHaplotype,
			sqlParams: kwargs.sqlParams,
            saveAs: 'existing')
        // cleanup the helper tables
        cleanup(sql, kwargs.variantGene, kwargs.sqlParams.job_id)
    }

    static def geneHaplotypeToNovelHaplotypeRedo(Map kwargs = [:], groovy.sql.Sql sql) {
        /* Do nothing, since it's already been done in variantToGeneHaplotypeRedo.
         */
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
                    dependencies.hetVariant = dependency(id: 'hetVariant', target: 'hetVariant', 
                    name: "Heterozygous Variant Combinations",
                    table: tbl.hetVariant,
                    fileUpload: canUpload('hetVariant')) {
                        dependencies.variant = dependency(id: 'variant', target: 'variant', 
                        name: "Variants",
                        table: tbl.variant,
                        fileUpload: canUpload('variant'))
                    }
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
        dependencies.novelHaplotype = builder.dependency(id: 'novelHaplotype', target: 'novelHaplotype', 
        name: "Novel Haplotypes",
        table: tbl.novelHaplotype,
        fileUpload: canUpload('novelHaplotype')) {
            dependency(refId: 'geneHaplotype')
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
            jobTables.each { _, jobTable ->
                sql.execute "delete from $jobTable where job_id = :jobId".toString(), kwargs
            }
        }

        /* Given a table alias and "raw" input (that is, in the sense that it may need to be 
         * filtered or error checked), build a SQL table from that input by inserting it with a new 
         * jobId.
         */
        def jobTableInsertColumns = jobTables.collect { it.key }.inject([:]) { m, alias ->
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
            variantToGeneHaplotypeRedo(pipelineKwargs, sql)
        }
        dependencies.hetVariant.rule = { ->
            variantToHetVariant(pipelineKwargs, sql)
        }
        dependencies.novelHaplotype.rule = { ->
            geneHaplotypeToNovelHaplotypeRedo(pipelineKwargs, sql)
        }
        dependencies.variant.rule = { ->
            if (kwargs.containsKey('variants')) {
                buildFromInput('variant', kwargs.variants)
            }
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
                if (table in jobTables && kwargs[inputKey] != null) {
                    def input = kwargs[inputKey]
                    dependencies[table].rule = { ->
                        buildFromInput(table, input)
                    }
                }
            }
        }

        return [kwargs.jobId, dependencies]
    }
	
	static def drugRecommendations(Map kwargs = [:], groovy.sql.Sql sql) {
        def (jobId, job) = pipelineJob(kwargs, sql)
        // For datasets that are already provided, insert their rows into the approriate job_* table, and mark them as built
        buildAll(job)
        return jobId
	}

    static def buildAll(job) {
        Set<Dependency> built = []
        buildAll(job, built)
    }

    static def buildAll(job, built) {
        job.novelHaplotype.build(built)
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
