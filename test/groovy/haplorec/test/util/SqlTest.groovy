package haplorec.test.util

import haplorec.util.Haplotype

import groovy.sql.Sql;
import groovy.util.GroovyTestCase

public class SqlTest extends DBTest {
	
	def TEST_DB = "haplorec_test"
	def TEST_HOST = "localhost"
	def TEST_PORT = 3306
	def TEST_USER = "root"
	def TEST_PASSWORD = ""

    def sql

    void setUp() {
        sql = setUpDB(TEST_DB,
                      host:TEST_HOST,
                      user:TEST_USER,
                      password:TEST_PASSWORD,
                      port:TEST_PORT)
    }

    void tearDown() {
        tearDownDB(TEST_DB)
    }

    def groupedRowsToColumnsTest(Map kwargs = [:], ACols, ARows, BCols, BRows, groupBy, columnMap) {
        try {

            // setup
			def createTable = { table, cols -> sql.execute "create table ${table}(${cols.collect { c -> c + ' integer'}.join(', ')})".toString() }
			createTable('A', ACols)
			createTable('B', BCols)
            def insertSql = { table, columns, rows ->
				if (rows.size() > 0) {
	                sql.execute """
	                insert into ${table}(${columns.join(', ')}) values
	                ${rows.collect { '(' + it.join(', ') + ')' }.join(', ')}
	                """.toString()
				}
            }
            insertSql('A', ACols, ARows)
            // test
			List badGroups = []
            Haplotype.groupedRowsToColumns(sql, 'A', 'B', groupBy, columnMap, orderRowsBy: kwargs.orderRowsBy, badGroup: { g -> badGroups.add(g) })
			def hashRowsToListRows = { rows, cols -> 
				rows.collect { r -> 
					cols.collect { r[it] } 
				} 
			}
            assertEquals(BRows, hashRowsToListRows(sql.rows('select * from B'), BCols))
			if (kwargs.badGroups != null) {
				def expect = kwargs.badGroups
				def got = badGroups.collect { g -> hashRowsToListRows(g, ACols) }
				assertEquals(expect, got)
			}
        } finally {
            // teardown
            sql.execute "drop table A".toString()
            sql.execute "drop table B".toString()
        }
    }
	
	void testGroupedRowsToColumns() {
		def ACols = ['x', 'y']
		def BCols = ['x', 'y1', 'y2']
		def groupBy = 'x'
		def columnMap = ['x':'x', 'y':['y1', 'y2']]
		groupedRowsToColumnsTest(
			ACols,
			[
				[1, 2],
				[1, 3],
			],
			BCols, 
			[
				[1, 2, 3],
			], groupBy, columnMap)
		groupedRowsToColumnsTest(
			ACols,
			[
				[1, 2],
			],
			BCols,
			[
				[1, 2, null],
			], groupBy, columnMap)
		groupedRowsToColumnsTest(
			ACols,
			[
				[1, 2],
			],
			BCols,
			[
				[1, null, 2],
			], groupBy,
			// fill 'y2' before filling 'y1', so that a group in A less than size two (that is, size 1) will make 'y1' null over 'y2' 
			['x':'x', 'y':['y2', 'y1']])
		groupedRowsToColumnsTest(
			ACols,
			[
				// without orderRowsBy: ['y'], we get [1, 3, 2] for the B row
				[1, 3],
				[1, 2],
			],
			BCols,
			[
				[1, 3, 2],
			], groupBy, columnMap)
		groupedRowsToColumnsTest(
			ACols,
			[
				// with orderRowsBy: ['y'], we get [1, 2, 3] for the B row
				[1, 3],
				[1, 2],
			],
			BCols,
			[
				[1, 2, 3],
			], groupBy, columnMap, orderRowsBy:['y'])
		// error cases
		groupedRowsToColumnsTest(
			ACols,
			[
				[1, 1],
				[1, 2],
				[1, 3],
			],
			BCols,
			[
			],
			badGroups:[
				[
					[1, 1],
					[1, 2],
					[1, 3],
				],
			],
		 	groupBy, columnMap)
		// empty input table
		groupedRowsToColumnsTest(
			ACols,
			[
			],
			BCols,
			[
			],
			badGroups:[
			],
			groupBy, columnMap)
	}

}