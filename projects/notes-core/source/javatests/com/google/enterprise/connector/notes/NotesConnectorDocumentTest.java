// Copyright 2011 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.notes;

import com.google.enterprise.connector.notes.client.NotesItem;
import com.google.enterprise.connector.notes.client.NotesSession;
import com.google.enterprise.connector.notes.client.mock.NotesDateTimeMock;
import com.google.enterprise.connector.notes.client.mock.NotesDatabaseMock;
import com.google.enterprise.connector.notes.client.mock.NotesDocumentMock;
import com.google.enterprise.connector.notes.client.mock.NotesItemMock;
import com.google.enterprise.connector.notes.client.mock.SessionFactoryMock;
import com.google.enterprise.connector.spi.Property;
import com.google.enterprise.connector.spi.SimpleTraversalContext;
import com.google.enterprise.connector.spi.SpiConstants.ActionType;
import com.google.enterprise.connector.spi.SpiConstants;
import com.google.enterprise.connector.spi.TraversalContextAware;
import com.google.enterprise.connector.spi.Value;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

public class NotesConnectorDocumentTest extends TestCase {

  private NotesConnector connector;
  private SessionFactoryMock factory;
  private boolean supportsInheritedAcls = false;

  public NotesConnectorDocumentTest() {
    super();
  }

  protected void setUp() throws Exception {
    super.setUp();
    connector = NotesConnectorTest.getConnector();
    factory = (SessionFactoryMock) connector.getSessionFactory();
    NotesConnectorSessionTest.configureFactoryForSession(factory);
    supportsInheritedAcls =
        Boolean.getBoolean("javatest.supportsinheritedacls");
  }

  protected void tearDown() {
    if (connector != null) {
      connector.shutdown();
    }
  }

  public void testSetMetaFieldsText() throws Exception {
    NotesConnectorDocument doc = new NotesConnectorDocument(null, null);
    doc.docProps = new HashMap<String, List<Value>>();

    NotesDocumentMock crawlDoc = new NotesDocumentMock();
    crawlDoc.addItem(new NotesItemMock("name", "x.foo", "type", NotesItem.TEXT,
            "values", "this is the text for field foo"));
    doc.crawlDoc = crawlDoc;
    doc.setMetaFields();
    Property p = doc.findProperty("foo");
    assertNotNull("property foo missing", p);
    Value v = p.nextValue();
    assertNotNull("property foo value missing", v);
    assertEquals("property foo", "this is the text for field foo",
        v.toString());
    assertNull(p.nextValue());
  }

  public void testSetMetaFieldsNumber() throws Exception {
    NotesConnectorDocument doc = new NotesConnectorDocument(null, null);
    doc.docProps = new HashMap<String, List<Value>>();

    NotesDocumentMock crawlDoc = new NotesDocumentMock();
    crawlDoc.addItem(new NotesItemMock("name", "x.foo", "type",
            NotesItem.NUMBERS, "values", new Double(11)));
    doc.crawlDoc = crawlDoc;
    doc.setMetaFields();
    Property p = doc.findProperty("foo");
    assertNotNull("property foo missing", p);
    Value v = p.nextValue();
    assertNotNull("property foo value missing", v);
    assertEquals("property foo", "11.0", v.toString());
    assertNull(p.nextValue());
  }

  public void testSetMetaFieldsDateTime() throws Exception {
    NotesConnectorDocument doc = new NotesConnectorDocument(null, null);
    doc.docProps = new HashMap<String, List<Value>>();

    Date testDate = new Date();
    Calendar testCalendar = Calendar.getInstance();
    testCalendar.setTime(testDate);

    NotesDocumentMock crawlDoc = new NotesDocumentMock();
    crawlDoc.addItem(new NotesItemMock("name", "x.foo", "type",
            NotesItem.NUMBERS, "values", new NotesDateTimeMock(testDate)));
    doc.crawlDoc = crawlDoc;
    doc.setMetaFields();
    Property p = doc.findProperty("foo");
    assertNotNull("property foo missing", p);
    Value v = p.nextValue();
    assertNotNull("property foo value missing", v);
    assertEquals("property foo",
        Value.calendarToIso8601(testCalendar), v.toString());
    assertNull(p.nextValue());
  }

  public void testSetMetaFieldsTextMultipleValues() throws Exception {
    NotesConnectorDocument doc = new NotesConnectorDocument(null, null);
    doc.docProps = new HashMap<String, List<Value>>();

    NotesDocumentMock crawlDoc = new NotesDocumentMock();
    crawlDoc.addItem(new NotesItemMock("name", "x.foo", "type", NotesItem.TEXT,
            "values", "foo text 1", "foo text 2", "foo text 3"));
    doc.crawlDoc = crawlDoc;
    doc.setMetaFields();
    Property p = doc.findProperty("foo");
    assertNotNull("property foo missing", p);
    Value v = p.nextValue();
    assertNotNull("property foo value missing", v);
    assertEquals("property foo 1", "foo text 1", v.toString());
    v = p.nextValue();
    assertEquals("property foo 2", "foo text 2", v.toString());
    v = p.nextValue();
    assertEquals("property foo 3", "foo text 3", v.toString());
    assertNull(p.nextValue());
  }

  public void testDeleteDocument() throws Exception {
    NotesDocumentMock crawlDoc = new NotesDocumentMock();
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_ACTION,
            "type", NotesItem.TEXT, "values", ActionType.DELETE));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_DOCID, "type",
            NotesItem.TEXT, "values", "docid"));

    NotesConnectorDocument document = new NotesConnectorDocument(null, null);
    document.setCrawlDoc("unid", crawlDoc);
    assertEquals(2, document.docProps.size());
    assertPropertyEquals("docid", document, SpiConstants.PROPNAME_DOCID);
    assertPropertyEquals(ActionType.DELETE.toString(), document,
        SpiConstants.PROPNAME_ACTION);
  }

  public void testAddDocument() throws Exception {
    NotesDocumentMock crawlDoc = new NotesDocumentMock();
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_ACTION,
            "type", NotesItem.TEXT, "values", ActionType.ADD));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_DOCID, "type",
            NotesItem.TEXT, "values", "http://host:42/replicaid/0/docid"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_TITLE, "type",
            NotesItem.TEXT, "values", "This is the title"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_MIMETYPE, "type",
            NotesItem.TEXT, "values", "text/plain"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_ISPUBLIC, "type",
            NotesItem.TEXT, "values", "true"));
    crawlDoc.addItem(new NotesItemMock("name",
            NCCONST.ITM_GMETADESCRIPTION, "type",
            NotesItem.TEXT, "values", "This is the description"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETADATABASE,
            "type", NotesItem.TEXT, "values", "crawled database"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETACATEGORIES,
            "type", NotesItem.TEXT, "values", "CATEGORY 1", "CATEGORY 2"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETAREPLICASERVERS,
            "type", NotesItem.TEXT, "values", "replica server"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETANOTESLINK,
            "type", NotesItem.TEXT, "values", "/notes/link"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETAWRITERNAME,
            "type", NotesItem.TEXT, "values", "An Author"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETAFORM, "type",
            NotesItem.TEXT, "values", "docform"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_CONTENT, "type",
            NotesItem.TEXT, "values", "This is the content"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_AUTHTYPE, "type",
            NotesItem.TEXT, "values", NCCONST.AUTH_ACL));

    Date testDate = new Date();
    Calendar testCalendar = Calendar.getInstance();
    testCalendar.setTime(testDate);
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETALASTUPDATE,
            "type", NotesItem.DATETIMES, "values", testDate));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETACREATEDATE,
            "type", NotesItem.DATETIMES, "values", testDate));

    NotesConnectorSession connectorSession =
        (NotesConnectorSession) connector.login();
    NotesConnectorDocument document = new NotesConnectorDocument(
        connectorSession, null);
    SimpleTraversalContext context = new SimpleTraversalContext();
    context.setSupportsInheritedAcls(supportsInheritedAcls);
    ((TraversalContextAware) connectorSession.getTraversalManager())
        .setTraversalContext(context);
    document.setCrawlDoc("unid", crawlDoc);

    assertPropertyEquals("http://host:42/replicaid/0/docid",
        document, SpiConstants.PROPNAME_DOCID);
    assertPropertyEquals(ActionType.ADD.toString(), document,
        SpiConstants.PROPNAME_ACTION);
    assertPropertyEquals("This is the title", document,
        SpiConstants.PROPNAME_TITLE);
    assertPropertyEquals("text/plain", document,
        SpiConstants.PROPNAME_MIMETYPE);
    assertPropertyEquals("http://host:42/replicaid/0/docid",
        document, SpiConstants.PROPNAME_DISPLAYURL);
    assertPropertyEquals("true", document, SpiConstants.PROPNAME_ISPUBLIC);
    assertPropertyEquals("CATEGORY 1", document, NCCONST.PROPNAME_NCCATEGORIES);
    assertPropertyEquals("CATEGORY 2", document, NCCONST.PROPNAME_NCCATEGORIES,
      1);
    assertPropertyEquals(Value.calendarToIso8601(testCalendar),
        document, SpiConstants.PROPNAME_LASTMODIFIED);
    if (supportsInheritedAcls) {
      assertPropertyEquals("http://host:42/replicaid/" +
          NCCONST.DB_ACL_INHERIT_TYPE_PARENTOVERRIDES,
          document, SpiConstants.PROPNAME_ACLINHERITFROM_DOCID);
    } else {
      assertNull(document.findProperty(
          SpiConstants.PROPNAME_ACLINHERITFROM_DOCID));
    }
    assertNull(document.findProperty(SpiConstants.PROPNAME_ACLUSERS));
    assertNull(document.findProperty(SpiConstants.PROPNAME_ACLGROUPS));
  }

  public void testAddDocumentWithReaders() throws Exception {
    NotesDocumentMock crawlDoc = new NotesDocumentMock();
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_ACTION,
            "type", NotesItem.TEXT, "values", ActionType.ADD));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_DOCID, "type",
            NotesItem.TEXT, "values", "http://host:42/replicaid/0/docid"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_REPLICAID,
            "type", NotesItem.TEXT, "values", "replicaid"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_TITLE, "type",
            NotesItem.TEXT, "values", "This is the title"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_MIMETYPE, "type",
            NotesItem.TEXT, "values", "text/plain"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_ISPUBLIC, "type",
            NotesItem.TEXT, "values", "true"));
    crawlDoc.addItem(new NotesItemMock("name",
            NCCONST.ITM_GMETADESCRIPTION, "type",
            NotesItem.TEXT, "values", "This is the description"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETADATABASE,
            "type", NotesItem.TEXT, "values", "crawled database"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETACATEGORIES,
            "type", NotesItem.TEXT, "values", "CATEGORY 1", "CATEGORY 2"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETAREPLICASERVERS,
            "type", NotesItem.TEXT, "values", "replica server"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETANOTESLINK,
            "type", NotesItem.TEXT, "values", "/notes/link"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETAWRITERNAME,
            "type", NotesItem.TEXT, "values", "An Author"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETAFORM, "type",
            NotesItem.TEXT, "values", "docform"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_CONTENT, "type",
            NotesItem.TEXT, "values", "This is the content"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_DOCAUTHORREADERS,
            "type", NotesItem.TEXT, "values", "cn=Test User/ou=Tests/o=Tests",
            "readergroup", "[readerrole]"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_AUTHTYPE, "type",
            NotesItem.TEXT, "values", NCCONST.AUTH_ACL));

    Date testDate = new Date();
    Calendar testCalendar = Calendar.getInstance();
    testCalendar.setTime(testDate);
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETALASTUPDATE,
            "type", NotesItem.DATETIMES, "values", testDate));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_GMETACREATEDATE,
            "type", NotesItem.DATETIMES, "values", testDate));

    NotesConnectorSession connectorSession =
        (NotesConnectorSession) connector.login();
    SimpleTraversalContext context = new SimpleTraversalContext();
    context.setSupportsInheritedAcls(supportsInheritedAcls);
    ((NotesTraversalManager) connectorSession.getTraversalManager())
        .setTraversalContext(context);
    NotesSession session = connectorSession.createNotesSession();
    NotesDatabaseMock connectorDatabase =
        (NotesDatabaseMock) session.getDatabase(
        connectorSession.getServer(), connectorSession.getDatabase());

    NotesConnectorDocument document = new NotesConnectorDocument(
        connectorSession, connectorDatabase);
    document.setCrawlDoc("unid", crawlDoc);

    assertPropertyEquals("http://host:42/replicaid/0/docid",
        document, SpiConstants.PROPNAME_DOCID);
    assertPropertyEquals(ActionType.ADD.toString(), document,
        SpiConstants.PROPNAME_ACTION);
    assertPropertyEquals("This is the title", document,
        SpiConstants.PROPNAME_TITLE);
    assertPropertyEquals("text/plain", document,
        SpiConstants.PROPNAME_MIMETYPE);
    assertPropertyEquals("http://host:42/replicaid/0/docid",
        document, SpiConstants.PROPNAME_DISPLAYURL);
    assertPropertyEquals("true", document, SpiConstants.PROPNAME_ISPUBLIC);
    assertPropertyEquals("CATEGORY 1", document, NCCONST.PROPNAME_NCCATEGORIES);
    assertPropertyEquals("CATEGORY 2", document, NCCONST.PROPNAME_NCCATEGORIES,
      1);
    assertPropertyEquals(Value.calendarToIso8601(testCalendar),
        document, SpiConstants.PROPNAME_LASTMODIFIED);
    if (supportsInheritedAcls) {
      assertPropertyEquals("http://host:42/replicaid/" +
          NCCONST.DB_ACL_INHERIT_TYPE_ANDBOTH,
          document, SpiConstants.PROPNAME_ACLINHERITFROM_DOCID);
      assertPropertyEquals("testuser", document,
          SpiConstants.PROPNAME_ACLUSERS);
      assertPropertyEquals("Domino%2Freadergroup", document,
          SpiConstants.PROPNAME_ACLGROUPS);
      assertPropertyEquals("Domino%2Freplicaid%2F%5Breaderrole%5D",
          document, SpiConstants.PROPNAME_ACLGROUPS, 1);
    } else {
      assertNull(document.findProperty(
              SpiConstants.PROPNAME_ACLINHERITFROM_DOCID));
      assertNull(document.findProperty(SpiConstants.PROPNAME_ACLUSERS));
      assertNull(document.findProperty(SpiConstants.PROPNAME_ACLGROUPS));
    }
  }

  public void testAddDatabaseAcl() throws Exception {
    if (!supportsInheritedAcls) {
      return;
    }
    // Mimic NotesDatabasePoller.createDatabaseAclDocument
    NotesDocumentMock crawlDoc = new NotesDocumentMock();
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_DBACL, "type",
            NotesItem.TEXT, "values", "true"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_DBACLINHERITTYPE,
            "type", NotesItem.TEXT, "values",
            NCCONST.DB_ACL_INHERIT_TYPE_ANDBOTH));

    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_STATE,
            "type", NotesItem.TEXT, "values", NCCONST.STATEFETCHED));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_ACTION,
            "type", NotesItem.TEXT, "values", ActionType.ADD));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_UNID, "type",
            NotesItem.TEXT, "values", "unid"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.ITM_DOCID, "type",
            NotesItem.TEXT, "values", "docid"));
    crawlDoc.addItem(new NotesItemMock("name", NCCONST.NCITM_DBPERMITUSERS,
            "type", NotesItem.TEXT, "values", "user1", "user2"));
    NotesConnectorDocument document = new NotesConnectorDocument(
        null, null);
    document.setCrawlDoc("unid", crawlDoc);

    assertPropertyEquals("docid", document, SpiConstants.PROPNAME_DOCID);
    assertPropertyEquals(ActionType.ADD.toString(), document,
        SpiConstants.PROPNAME_ACTION);
    assertPropertyEquals(
        SpiConstants.AclInheritanceType.AND_BOTH_PERMIT.toString(),
        document, SpiConstants.PROPNAME_ACLINHERITANCETYPE);
    assertPropertyEquals("user1", document, SpiConstants.PROPNAME_ACLUSERS);
    assertPropertyEquals("user2", document, SpiConstants.PROPNAME_ACLUSERS, 1);
  }

  private void assertPropertyEquals(String expected,
      NotesConnectorDocument document, String property) throws Exception {
    assertPropertyEquals(expected, document, property, 0);
  }

  private void assertPropertyEquals(String expected,
      NotesConnectorDocument document, String property, int index)
      throws Exception {
    Property p = document.findProperty(property);
    assertNotNull("Missing property " + property, p);

    int i = 0;
    Value v;
    while ((v = p.nextValue()) != null) {
      if (i == index) {
        assertEquals(expected, v.toString());
        return;
      }
      i++;
    }
    fail("No value for property at index: " + property + "/" + index);
  }
}
