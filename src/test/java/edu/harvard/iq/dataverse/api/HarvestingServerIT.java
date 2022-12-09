package edu.harvard.iq.dataverse.api;

import java.util.logging.Level;
import java.util.logging.Logger;
import com.jayway.restassured.RestAssured;
import static com.jayway.restassured.RestAssured.given;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import com.jayway.restassured.response.Response;
import com.jayway.restassured.path.xml.XmlPath;
import com.jayway.restassured.path.xml.element.Node;
import java.util.ArrayList;
import java.util.Collections;
import static javax.ws.rs.core.Response.Status.OK;
import static org.hamcrest.CoreMatchers.equalTo;
import java.util.List;
//import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/**
 * Tests for the Harvesting Server functionality
 * Note that these test BOTH the proprietary Dataverse rest APIs for creating 
 * and managing sets, AND the OAI-PMH functionality itself.
 */
public class HarvestingServerIT {

    private static final Logger logger = Logger.getLogger(HarvestingServerIT.class.getCanonicalName());

    private static String normalUserAPIKey;
    private static String adminUserAPIKey;
    private static String singleSetDatasetIdentifier;
    private static String singleSetDatasetPersistentId;

    @BeforeClass
    public static void setUpClass() {
        RestAssured.baseURI = UtilIT.getRestAssuredBaseUri();
	// enable harvesting server
	//  Gave some thought to storing the original response, and resetting afterwards - but that appears to be more complexity than it's worth
	Response enableHarvestingServerResponse = UtilIT.setSetting(SettingsServiceBean.Key.OAIServerEnabled,"true");
        
        // Create users:
        setupUsers();
        
        // Create and publish some datasets: 
        setupDatasets();
        
    }

    @AfterClass
    public static void afterClass() {
	// disable harvesting server (default value)
	Response enableHarvestingServerResponse = UtilIT.setSetting(SettingsServiceBean.Key.OAIServerEnabled,"false");
    }

    private static void setupUsers() {
        Response cu0 = UtilIT.createRandomUser();
        normalUserAPIKey = UtilIT.getApiTokenFromResponse(cu0);
        Response cu1 = UtilIT.createRandomUser();
        String un1 = UtilIT.getUsernameFromResponse(cu1);
        Response u1a = UtilIT.makeSuperUser(un1);
        adminUserAPIKey = UtilIT.getApiTokenFromResponse(cu1);
    }
    
    private static void setupDatasets() {
        // create dataverse:
        Response createDataverseResponse = UtilIT.createRandomDataverse(adminUserAPIKey);
        createDataverseResponse.prettyPrint();
        String dataverseAlias = UtilIT.getAliasFromResponse(createDataverseResponse);

        // publish dataverse:
        Response publishDataverse = UtilIT.publishDataverseViaNativeApi(dataverseAlias, adminUserAPIKey);
        assertEquals(OK.getStatusCode(), publishDataverse.getStatusCode());

        // create dataset: 
        Response createDatasetResponse = UtilIT.createRandomDatasetViaNativeApi(dataverseAlias, adminUserAPIKey);
        createDatasetResponse.prettyPrint();
        Integer datasetId = UtilIT.getDatasetIdFromResponse(createDatasetResponse);

        // retrieve the global id: 
        singleSetDatasetPersistentId = UtilIT.getDatasetPersistentIdFromResponse(createDatasetResponse);

        // publish dataset:
        Response publishDataset = UtilIT.publishDatasetViaNativeApi(singleSetDatasetPersistentId, "major", adminUserAPIKey);
        assertEquals(200, publishDataset.getStatusCode());

        singleSetDatasetIdentifier = singleSetDatasetPersistentId.substring(singleSetDatasetPersistentId.lastIndexOf('/') + 1);

        logger.info("identifier: " + singleSetDatasetIdentifier);
        
        // Publish command is executed asynchronously, i.e. it may 
        // still be running after we received the OK from the publish API. 
        // The oaiExport step also requires the metadata exports to be done and this
        // takes longer than just publish/reindex.
        // So wait for all of this to finish.
        UtilIT.sleepForReexport(singleSetDatasetPersistentId, adminUserAPIKey, 10);
    }

    private String jsonForTestSpec(String name, String def) {
        String r = String.format("{\"name\":\"%s\",\"definition\":\"%s\"}", name, def);//description is optional
        return r;
    }
    
    private String jsonForEditSpec(String name, String def, String desc) {
        String r = String.format("{\"name\":\"%s\",\"definition\":\"%s\",\"description\":\"%s\"}", name, def, desc);
        return r;
    }

    private XmlPath validateOaiVerbResponse(Response oaiResponse, String verb) {
        // confirm that the response is in fact XML:
        XmlPath responseXmlPath = oaiResponse.getBody().xmlPath();
        assertNotNull(responseXmlPath);
        
        String dateString = responseXmlPath.getString("OAI-PMH.responseDate");
        assertNotNull(dateString); // TODO: validate that it's well-formatted!
        logger.info("date string from the OAI output:"+dateString);
        assertEquals("http://localhost:8080/oai", responseXmlPath.getString("OAI-PMH.request"));
        assertEquals(verb, responseXmlPath.getString("OAI-PMH.request.@verb"));
        return responseXmlPath;
    }
    
    @Test 
    public void testOaiIdentify() {
        // Run Identify:
        Response identifyResponse = UtilIT.getOaiIdentify();
        assertEquals(OK.getStatusCode(), identifyResponse.getStatusCode());
        //logger.info("Identify response: "+identifyResponse.prettyPrint());

        // Validate the response: 
        
        XmlPath responseXmlPath = validateOaiVerbResponse(identifyResponse, "Identify");
        assertEquals("http://localhost:8080/oai", responseXmlPath.getString("OAI-PMH.Identify.baseURL"));
        // Confirm that the server is reporting the correct parameters that 
        // our server implementation should be using:
        assertEquals("2.0", responseXmlPath.getString("OAI-PMH.Identify.protocolVersion"));
        assertEquals("transient", responseXmlPath.getString("OAI-PMH.Identify.deletedRecord"));
        assertEquals("YYYY-MM-DDThh:mm:ssZ", responseXmlPath.getString("OAI-PMH.Identify.granularity"));
    }
    
    @Test
    public void testOaiListMetadataFormats() {
        // Run ListMeatadataFormats:
        Response listFormatsResponse = UtilIT.getOaiListMetadataFormats();
        assertEquals(OK.getStatusCode(), listFormatsResponse.getStatusCode());
        //logger.info("ListMetadataFormats response: "+listFormatsResponse.prettyPrint());

        // Validate the response: 
        
        XmlPath responseXmlPath = validateOaiVerbResponse(listFormatsResponse, "ListMetadataFormats");
        
        // Check the payload of the response atgainst the list of metadata formats
        // we are currently offering under OAI; will need to be explicitly 
        // modified if/when we add more harvestable formats.
        
        List listFormats = responseXmlPath.getList("OAI-PMH.ListMetadataFormats.metadataFormat");

        assertNotNull(listFormats);
        assertEquals(5, listFormats.size());
        
        // The metadata formats are reported in an unpredictable ordder. We
        // want to sort the prefix names for comparison purposes, and for that 
        // they need to be saved in a modifiable list: 
        List<String> metadataPrefixes = new ArrayList<>(); 
        
        for (int i = 0; i < listFormats.size(); i++) {
            metadataPrefixes.add(responseXmlPath.getString("OAI-PMH.ListMetadataFormats.metadataFormat["+i+"].metadataPrefix"));
        }
        Collections.sort(metadataPrefixes);
        
        assertEquals("[Datacite, dataverse_json, oai_datacite, oai_dc, oai_ddi]", metadataPrefixes.toString());
        

    }
    
    
    @Test
    public void testNativeSetAPI() {
        String setName = UtilIT.getRandomString(6);
        String def = "*";
        
        // This test focuses on the Create/List/Edit functionality of the 
        // Dataverse OAI Sets API (/api/harvest/server):
 
        // API Test 1. Make sure the set does not exist yet
        String setPath = String.format("/api/harvest/server/oaisets/%s", setName);
        String createPath ="/api/harvest/server/oaisets/add";
        Response getSetResponse = given()
                .get(setPath);
        assertEquals(404, getSetResponse.getStatusCode());

        // API Test 2. Try to create set as normal user, should fail
        Response createSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, normalUserAPIKey)
                .body(jsonForTestSpec(setName, def))
                .post(createPath);
        assertEquals(400, createSetResponse.getStatusCode());

        // API Test 3. Try to create set as admin user, should succeed
        createSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, adminUserAPIKey)
                .body(jsonForTestSpec(setName, def))
                .post(createPath);
        assertEquals(201, createSetResponse.getStatusCode());
        
        // API Test 4. Retrieve the set we've just created, validate the response
        getSetResponse = given().get(setPath);
        
        System.out.println("getSetResponse.getStatusCode(): " + getSetResponse.getStatusCode());
        System.out.println("getSetResponse, full:  " + getSetResponse.prettyPrint());
        assertEquals(200, getSetResponse.getStatusCode());
        
        getSetResponse.then().assertThat()
                .body("status", equalTo(AbstractApiBean.STATUS_OK))
                .body("data.definition", equalTo("*"))
                .body("data.description", equalTo(""))
                .body("data.name", equalTo(setName));
        
        
        // API Test 5. Retrieve all sets, check that our new set is listed 
        Response responseAll = given()
                .get("/api/harvest/server/oaisets");
        
        System.out.println("responseAll.getStatusCode(): " + responseAll.getStatusCode());
        System.out.println("responseAll full:  " + responseAll.prettyPrint());
        assertEquals(200, responseAll.getStatusCode());
        assertTrue(responseAll.body().jsonPath().getList("data.oaisets").size() > 0);
        assertTrue(responseAll.body().jsonPath().getList("data.oaisets.name").toString().contains(setName));  // todo: simplify     
        
        // API Test 6. Try to create a set with the same name, should fail
        createSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, adminUserAPIKey)
                .body(jsonForTestSpec(setName, def))
                .post(createPath);
        assertEquals(400, createSetResponse.getStatusCode());

        // API Test 7. Try to export set as admin user, should succeed. Set export
        // is under /api/admin, no need to try to access it as a non-admin user
        Response r4 = UtilIT.exportOaiSet(setName);
        assertEquals(200, r4.getStatusCode());
                
        // API TEST 8. Try to delete the set as normal user, should fail
        Response deleteResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, normalUserAPIKey)
                .delete(setPath);
        logger.info("deleteResponse.getStatusCode(): " + deleteResponse.getStatusCode());
        assertEquals(400, deleteResponse.getStatusCode());
        
        // API TEST 9. Delete as admin user, should work
        deleteResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, adminUserAPIKey)
                .delete(setPath);
        logger.info("deleteResponse.getStatusCode(): " + deleteResponse.getStatusCode());
        assertEquals(200, deleteResponse.getStatusCode());

    }
    
    @Test
    public void testSetEditAPIandOAIlistSets() {
        // This test focuses on testing the Edit functionality of the Dataverse
        // OAI Set API and the ListSets method of the Dataverse OAI server.
        
        // Initial setup: crete a test set. 
        // Since the Create and List (POST and GET) functionality of the API 
        // is tested extensively in the previous test, we will not be paying 
        // as much attention to these methods, aside from confirming the 
        // expected HTTP result codes. 
        
        String setName = UtilIT.getRandomString(6);
        String setDef = "*";

        // Make sure the set does not exist
        String setPath = String.format("/api/harvest/server/oaisets/%s", setName);
        String createPath ="/api/harvest/server/oaisets/add";
        Response getSetResponse = given()
                .get(setPath);
        assertEquals(404, getSetResponse.getStatusCode());


        // Create the set as admin user
        Response createSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, adminUserAPIKey)
                .body(jsonForTestSpec(setName, setDef))
                .post(createPath);
        assertEquals(201, createSetResponse.getStatusCode());

        // I. Test the Modify/Edit (POST method) functionality of the 
        // Dataverse OAI Sets API
        
        String newDefinition = "title:New";
        String newDescription = "updated";
        
        // API Test 1. Try to modify the set as normal user, should fail
        Response editSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, normalUserAPIKey)
                .body(jsonForEditSpec(setName, setDef, ""))
                .put(setPath);
        logger.info("non-admin user editSetResponse.getStatusCode(): " + editSetResponse.getStatusCode());
        assertEquals(400, editSetResponse.getStatusCode());
        
        // API Test 2. Try to modify as admin, but with invalid (empty) values, 
        // should fail
        editSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, adminUserAPIKey)
                .body(jsonForEditSpec(setName, "",""))
                .put(setPath);
        logger.info("invalid values editSetResponse.getStatusCode(): " + editSetResponse.getStatusCode());
        assertEquals(400, editSetResponse.getStatusCode());
        
        // API Test 3. Try to modify as admin, with sensible values
        editSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, adminUserAPIKey)
                .body(jsonForEditSpec(setName, newDefinition, newDescription))
                .put(setPath);
        logger.info("admin user editSetResponse status code: " + editSetResponse.getStatusCode());
        logger.info("admin user editSetResponse.prettyPrint(): " + editSetResponse.prettyPrint());
        assertEquals(OK.getStatusCode(), editSetResponse.getStatusCode());
        
        // API Test 4. List the set, confirm that the new values are shown
        getSetResponse = given().get(setPath);
        
        System.out.println("getSetResponse.getStatusCode(): " + getSetResponse.getStatusCode());
        System.out.println("getSetResponse, full:  " + getSetResponse.prettyPrint());
        assertEquals(200, getSetResponse.getStatusCode());
        
        getSetResponse.then().assertThat()
                .body("status", equalTo(AbstractApiBean.STATUS_OK))
                .body("data.definition", equalTo(newDefinition))
                .body("data.description", equalTo(newDescription))
                .body("data.name", equalTo(setName));

        // II. Test the ListSets functionality of the OAI server 
        
        Response listSetsResponse = UtilIT.getOaiListSets();
        
        // 1. Validate the service section of the OAI response: 
        
        XmlPath responseXmlPath = validateOaiVerbResponse(listSetsResponse, "ListSets");
        
        // 2. Validate the payload of the response, by confirming that the set 
        // we created and modified, above, is being listed by the OAI server 
        // and its xml record is properly formatted
        
        List<Node> listSets = responseXmlPath.getList("OAI-PMH.ListSets.set.list()"); // TODO - maybe try it with findAll()?
        assertNotNull(listSets);
        assertTrue(listSets.size() > 0);

        Node foundSetNode = null; 
        for (Node setNode : listSets) {
            
            if (setName.equals(setNode.get("setName").toString())) {
                foundSetNode = setNode; 
                break;
            }
        }
        
        assertNotNull("Newly-created set is not listed by the OAI server", foundSetNode);
        assertEquals("Incorrect description in the ListSets entry", newDescription, foundSetNode.getPath("setDescription.metadata.element.field", String.class));

        // ok, the xml record looks good! 

        // Cleanup. Delete the set with the DELETE API
        Response deleteSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, adminUserAPIKey)
                .delete(setPath);
        assertEquals(200, deleteSetResponse.getStatusCode());

    }

    // A more elaborate test - we will create and export an 
    // OAI set with a single dataset, and attempt to retrieve 
    // it and validate the OAI server responses of the corresponding 
    // ListIdentifiers, ListRecords and GetRecord methods. 
    @Test
    public void testSingleRecordOaiSet() throws InterruptedException {
        // Let's try and create an OAI set with the "single set dataset" that 
        // was created as part of the initial setup:
        
        String setName = singleSetDatasetIdentifier;
        String setQuery = "dsPersistentId:" + singleSetDatasetIdentifier;
        String apiPath = String.format("/api/harvest/server/oaisets/%s", setName);
        String createPath ="/api/harvest/server/oaisets/add";
        Response createSetResponse = given()
                .header(UtilIT.API_TOKEN_HTTP_HEADER, adminUserAPIKey)
                .body(jsonForTestSpec(setName, setQuery))
                .post(createPath);
        assertEquals(201, createSetResponse.getStatusCode());

        // The GET method of the oai set API, as well as the OAI ListSets
        // method are tested extensively in another method in this class, so 
        // we'll skip checking those here. 
        
        // Let's export the set. This is asynchronous - so we will try to 
        // wait a little - but in practice, everything potentially time-consuming
        // must have been done when the dataset was exported, in the setup method. 
        
        Response exportSetResponse = UtilIT.exportOaiSet(setName);
        assertEquals(200, exportSetResponse.getStatusCode());
        Thread.sleep(1000L);
        
        Response getSet = given()
                .get(apiPath);
        
        logger.info("getSet.getStatusCode(): " + getSet.getStatusCode());
        logger.fine("getSet printresponse:  " + getSet.prettyPrint());
        assertEquals(200, getSet.getStatusCode());
        int i = 0;
        int maxWait=10;
        do {
            

            // OAI Test 1. Run ListIdentifiers on this newly-created set:
            Response listIdentifiersResponse = UtilIT.getOaiListIdentifiers(setName, "oai_dc");
            assertEquals(OK.getStatusCode(), listIdentifiersResponse.getStatusCode());
            
            // Validate the service section of the OAI response: 
            XmlPath responseXmlPath = validateOaiVerbResponse(listIdentifiersResponse, "ListIdentifiers");
            
            List ret = responseXmlPath.getList("OAI-PMH.ListIdentifiers.header");
            assertNotNull(ret);
                        
            if (logger.isLoggable(Level.FINE)) {
                logger.info("listIdentifiersResponse.prettyPrint:..... ");
                listIdentifiersResponse.prettyPrint();
            }
            if (ret.isEmpty()) {
                // OK, we'll sleep for another second - provided it's been less
                // than 10 sec. total.
                i++;
            } else {
                // Validate the payload of the ListRecords response:
                // a) There should be 1 and only 1 record in the response:
                assertEquals(1, ret.size());
                // b) The one record in it should be the dataset we have just created:
                assertEquals(singleSetDatasetPersistentId, responseXmlPath
                        .getString("OAI-PMH.ListIdentifiers.header.identifier"));
                assertEquals(setName, responseXmlPath
                        .getString("OAI-PMH.ListIdentifiers.header.setSpec"));
                assertNotNull(responseXmlPath.getString("OAI-PMH.ListIdentifiers.header.dateStamp"));
                // TODO: validate the formatting of the date string in the record
                // header, above!
                
                // ok, ListIdentifiers response looks valid.
                break;
            }
            Thread.sleep(1000L);
        } while (i<maxWait); 
        // OK, the code above that expects to have to wait for up to 10 seconds 
        // for the set to export is most likely utterly unnecessary (the potentially
        // expensive part of the operation - exporting the metadata of our dataset -
        // already happened during its publishing (we made sure to wait there). 
        // Exporting the set should not take any time - but I'll keep that code 
        // in place since it's not going to hurt. - L.A. 
        
        System.out.println("Waited " + i + " seconds for OIA export.");
        //Fail if we didn't find the exported record before the timeout
        assertTrue(i < maxWait);
        
        
        // OAI Test 2. Run ListRecords, request oai_dc:
        Response listRecordsResponse = UtilIT.getOaiListRecords(setName, "oai_dc");
        assertEquals(OK.getStatusCode(), listRecordsResponse.getStatusCode());
        
        // Validate the service section of the OAI response: 
        
        XmlPath responseXmlPath = validateOaiVerbResponse(listRecordsResponse, "ListRecords");
        
        // Validate the payload of the response: 
        // (the header portion must be identical to that of ListIdentifiers above, 
        // plus the response must contain a metadata section with a valid oai_dc 
        // record)
        
        List listRecords = responseXmlPath.getList("OAI-PMH.ListRecords.record");

        // Same deal, there must be 1 record only in the set:
        assertNotNull(listRecords);
        assertEquals(1, listRecords.size());
        // a) header section:
        assertEquals(singleSetDatasetPersistentId, responseXmlPath.getString("OAI-PMH.ListRecords.record.header.identifier"));
        assertEquals(setName, responseXmlPath
                .getString("OAI-PMH.ListRecords.record.header.setSpec"));
        assertNotNull(responseXmlPath.getString("OAI-PMH.ListRecords.record.header.dateStamp"));
        // b) metadata section: 
        // in the metadata section we are showing the resolver url form of the doi:
        String persistentIdUrl = singleSetDatasetPersistentId.replace("doi:", "https://doi.org/");
        assertEquals(persistentIdUrl, responseXmlPath.getString("OAI-PMH.ListRecords.record.metadata.dc.identifier"));
        assertEquals("Darwin's Finches", responseXmlPath.getString("OAI-PMH.ListRecords.record.metadata.dc.title"));
        assertEquals("Finch, Fiona", responseXmlPath.getString("OAI-PMH.ListRecords.record.metadata.dc.creator"));        
        assertEquals("Darwin's finches (also known as the Galápagos finches) are a group of about fifteen species of passerine birds.", 
                responseXmlPath.getString("OAI-PMH.ListRecords.record.metadata.dc.description"));
        assertEquals("Medicine, Health and Life Sciences", 
                responseXmlPath.getString("OAI-PMH.ListRecords.record.metadata.dc.subject"));
        // ok, looks legit!
        
        // OAI Test 3.
        // Assert that Datacite format does not contain the XML prolog
        // (this is a reference to a resolved issue; generally, harvestable XML
        // exports must NOT contain the "<?xml ..." headers - but there is now
        // efficient code in the XOAI library that checks for, and strips it, 
        // if necessary. - L.A.)
        Response listRecordsResponseDatacite = UtilIT.getOaiListRecords(setName, "Datacite");
        assertEquals(OK.getStatusCode(), listRecordsResponseDatacite.getStatusCode());
        String body = listRecordsResponseDatacite.getBody().asString();
        assertFalse(body.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));

        // OAI Test 4. run and validate GetRecord response
        
        Response getRecordResponse = UtilIT.getOaiRecord(singleSetDatasetPersistentId, "oai_dc");
        System.out.println("GetRecord response in its entirety: "+getRecordResponse.getBody().prettyPrint());
         
        // Validate the service section of the OAI response: 
        responseXmlPath = validateOaiVerbResponse(getRecordResponse, "GetRecord");
        
        // Validate the payload of the response:
        
        // Note that for a set with a single record the output of ListRecrods is
        // essentially identical to that of GetRecord!
        // (we'll test a multi-record set in a different method)
        // a) header section:
        assertEquals(singleSetDatasetPersistentId, responseXmlPath.getString("OAI-PMH.GetRecord.record.header.identifier"));
        assertEquals(setName, responseXmlPath
                .getString("OAI-PMH.GetRecord.record.header.setSpec"));
        assertNotNull(responseXmlPath.getString("OAI-PMH.GetRecord.record.header.dateStamp"));
        // b) metadata section: 
        assertEquals(persistentIdUrl, responseXmlPath.getString("OAI-PMH.GetRecord.record.metadata.dc.identifier"));
        assertEquals("Darwin's Finches", responseXmlPath.getString("OAI-PMH.GetRecord.record.metadata.dc.title"));
        assertEquals("Finch, Fiona", responseXmlPath.getString("OAI-PMH.GetRecord.record.metadata.dc.creator"));        
        assertEquals("Darwin's finches (also known as the Galápagos finches) are a group of about fifteen species of passerine birds.", 
                responseXmlPath.getString("OAI-PMH.GetRecord.record.metadata.dc.description"));
        assertEquals("Medicine, Health and Life Sciences", responseXmlPath.getString("OAI-PMH.GetRecord.record.metadata.dc.subject"));
        
        // ok, looks legit!

    }
    
    // This test will attempt to create a set with multiple records (enough 
    // to trigger a paged response with a continuation token) and test its
    // performance. 
    
    
    @Test
    public void testMultiRecordOaiSet() throws InterruptedException {
        
    }
}
