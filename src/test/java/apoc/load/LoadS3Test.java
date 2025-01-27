package apoc.load;

import apoc.util.TestUtil;
import apoc.util.Util;
import org.junit.*;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.test.TestGraphDatabaseFactory;

import static apoc.load.LoadCsvTest.assertRow;
import static apoc.load.XmlTest.XML_XPATH_AS_NESTED_MAP;
import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

public class LoadS3Test {

    private GraphDatabaseService db;
    private MinioSetUp minio;

    @BeforeClass
    public static void init() {
        // In test environment we skip the MD5 validation that can cause issues
        System.setProperty("com.amazonaws.services.s3.disableGetObjectMD5Validation", "true");
    }

    @AfterClass
    public static void destroy() {
        System.clearProperty("com.amazonaws.services.s3.disableGetObjectMD5Validation");
    }

    @Before public void setUp() throws Exception {
        db = new TestGraphDatabaseFactory().newImpermanentDatabaseBuilder()
                .setConfig("apoc.import.file.use_neo4j_config", "false")
                .setConfig("apoc.import.file.enabled","true")
                .newGraphDatabase();
        TestUtil.registerProcedure(db, LoadCsv.class, LoadJson.class, Xml.class);
        minio = new MinioSetUp("dddbucketddd");
    }

    @After public void tearDown() throws Exception {
        db.shutdown();

        // The line below is quite flaky, but we don't want it to fail the build
        try {
            minio.deleteAll();
        } catch(Exception ignored) {

        }
    }

    @Test
    public void testLoadCsvS3() throws Exception {
        String url = minio.putFile("src/test/resources/test.csv");
        testResult(db, "CALL apoc.load.csv({url},{failOnError:false})", map("url", url), (r) -> {
            assertRow(r, "Selma", "8", 0L);
            assertRow(r, "Rana", "11", 1L);
            assertRow(r, "Selina", "18", 2L);
            assertEquals(false, r.hasNext());
        });
    }

    @Test public void testLoadJsonS3() throws Exception {
        String url = minio.putFile("src/test/resources/map.json");

        testCall(db, "CALL apoc.load.json({url},'')",map("url", url),
                (row) -> {
                    assertEquals(map("foo",asList(1L,2L,3L)), row.get("value"));
                });
    }

    @Test public void testLoadXmlS3() throws Exception {
        String url = minio.putFile("src/test/resources/xml/books.xml");

        testCall(db, "CALL apoc.load.xml({url},'/catalog/book[title=\"Maeve Ascendant\"]/.',{failOnError:false}) yield value as result", Util.map("url", url), (r) -> {
            assertEquals(XML_XPATH_AS_NESTED_MAP, r.get("result"));
        });
    }


}