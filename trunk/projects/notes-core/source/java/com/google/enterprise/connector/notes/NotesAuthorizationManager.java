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

import com.google.enterprise.connector.notes.client.NotesDatabase;
import com.google.enterprise.connector.notes.client.NotesDocument;
import com.google.enterprise.connector.notes.client.NotesSession;
import com.google.enterprise.connector.notes.client.NotesThread;
import com.google.enterprise.connector.notes.client.NotesView;
import com.google.enterprise.connector.notes.client.NotesViewEntry;
import com.google.enterprise.connector.notes.client.NotesViewNavigator;
import com.google.enterprise.connector.spi.AuthenticationIdentity;
import com.google.enterprise.connector.spi.AuthorizationManager;
import com.google.enterprise.connector.spi.AuthorizationResponse;
import com.google.enterprise.connector.spi.RepositoryException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

class NotesAuthorizationManager implements AuthorizationManager {
  private static final String CLASS_NAME =
      NotesAuthorizationManager.class.getName();
  private static final Logger LOGGER = Logger.getLogger(CLASS_NAME);

  private NotesConnectorSession ncs = null;

  public NotesAuthorizationManager(NotesConnectorSession session) {
    final String METHOD = "NotesAuthorizationManager";
    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
        "NotesAuthorizationManager being created.");
    ncs = session;
  }

  /* The docid is generated by the application and always takes the format
   * http://server.domain/ReplicaID/0/UniversalDocumentID
   * The protocol is always http://
   *
   * TODO: We need better documentation for the expected docid
   * format here. Plus constants (preferably generated
   * ones). Also, what happens if the docid uses HTTPS?
   *
   * Consider using java.net.URL and String.split to get the
   * pieces. Attachment docid values can be longer.
   */
  protected String getRepIdFromDocId(String docId) {
    int start = docId.indexOf('/', 7);  // Find the first slash after http://
    return docId.substring(start + 1, start + 17);
  }

  /* The docid is generated by the application and always takes the format
   * http://server.domain/ReplicaID/0/UniversalDocumentID
   * The protocol is always http://
   */
  protected String getUNIDFromDocId(String docId) {
    int start = docId.indexOf('/', 7);  // Find the first slash after http://
    return docId.substring(start + 20, start + 52);
  }

  // Explain Lotus Notes Authorization Rules

  // TODO: Add LRU Cache for ALLOW/DENY
  /* @Override */
  @SuppressWarnings("unchecked")
  public Collection<AuthorizationResponse> authorizeDocids(
      Collection<String> docIds, AuthenticationIdentity id) {
    final String METHOD = "authorizeDocids";
    String NotesName = null;
    Vector<String> UserGroups = null;
    ArrayList<AuthorizationResponse> authorized =
        new ArrayList<AuthorizationResponse>(docIds.size());
    String pvi = id.getUsername();
    NotesSession ns = null;
    try {
      LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
          "Authorizing documents for user " + pvi);
      ns = ncs.createNotesSession();

      NotesDatabase cdb = ns.getDatabase(ncs.getServer(), ncs.getDatabase());

      // TODO: Some of this code is very similar to code
      // elsewhere (esp.  NotesAuthenticationManager), with the
      // exception of the securityView in the middle (but unused
      // until later). Extract a helper method somewhere?
      NotesDatabase acdb = ns.getDatabase(null, null);
      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD, "Opening ACL database " +
          ncs.getServer() + " : " + ncs.getACLDbReplicaId());

      acdb.openByReplicaID(ncs.getServer(), ncs.getACLDbReplicaId());
      NotesView securityView = cdb.getView(NCCONST.VIEWSECURITY);
      NotesView people = acdb.getView(NCCONST.VIEWACPEOPLE);

      // Resolve the PVI to their Notes names and groups
      NotesDocument personDoc =
          people.getDocumentByKey(id.getUsername(), true);
      if (null == personDoc) {
        // Changed log level to FINE as an AuthZ message.
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Person not found in ACL database. DENY all docs. " + pvi);
      } else {
        NotesName = personDoc.getItemValueString(NCCONST.ACITM_NOTESNAME)
            .toLowerCase();

        // TODO: Logged string differs here and in
        // NotesAuthenticationManager. This could all be part of
        // the helper method.
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "PVI:NOTESNAME mapping is " + pvi + ":" + NotesName);
        UserGroups = (Vector<String>) personDoc.getItemValue(
            NCCONST.ACITM_GROUPS);
        for (int i = 0; i < UserGroups.size(); i++) {
          UserGroups.set(i, UserGroups.elementAt(i).toString().toLowerCase());
        }
        LOGGER.logp(Level.FINE, CLASS_NAME, METHOD,
            "Groups for " + pvi + " are: " + UserGroups.toString());
      }

      // The first document in the category will always be the database document

      for (String docId : docIds) {
        // Extract the database and UNID from the URL
        String repId = getRepIdFromDocId(docId);
        String unid = getUNIDFromDocId(docId);
        // Per-document log message
        LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
            "Authorizing documents for user " + pvi + " : " +
            repId + " : " + unid);

        if (null == personDoc) {
          // We didn't find this person, so deny all.
          // Alternatively we could return INDETERMINATE...
          authorized.add(new AuthorizationResponse(false, docId));
          continue;
        }

        boolean docallow = true;
        // Get the category from the security view for this database
        NotesViewNavigator secVN =
            securityView.createViewNavFromCategory(repId);
        // The first document in the category is ALWAYS the database document
        NotesDocument dbdoc = secVN.getFirstDocument().getDocument();
        // If there is more than one document in the category, we
        // will need to check for document level reader access
        // lists
        int securityCount = secVN.getCount();
        LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
            "Count for viewNavigator is  " + securityCount);

        boolean dballow = checkDatabaseAccess(NotesName, dbdoc, UserGroups);

        // Only check document level security if it exists
        if (dballow && (securityCount > 1)) {
          Vector<String> searchKey = new Vector<String>(3);
          searchKey.addElement(repId);
          // Database documents are type '1' in this view.
          // Crawler documents are type '2'
          searchKey.addElement("2");
          searchKey.addElement(unid);
          LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
              "Search key is  " + searchKey.toString());
          NotesDocument crawlDoc =
              securityView.getDocumentByKey(searchKey, true);
          if (crawlDoc != null) {
            // Found a crawldoc, so we will need to check document level access
            docallow = checkDocumentReaders(NotesName, UserGroups, crawlDoc,
                dbdoc);
            crawlDoc.recycle();
          } else {
            // There is no crawldoc with reader lists
            // restrictions so nothing to do
            LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
                "No document level security for " + unid);
          }
        }
        secVN.recycle();
        dbdoc.recycle();
        boolean allow = docallow && dballow;
        LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
            "Final auth decision is " + allow + " " + unid);
        authorized.add(new AuthorizationResponse(allow, docId));
      }

      if (null != personDoc) {
        personDoc.recycle();
      }
      if (null != securityView) {
        securityView.recycle();
      }
      if (null != people) {
        people.recycle();
      }
      if (null != cdb) {
        cdb.recycle();
      }
      if (null != acdb) {
        acdb.recycle();
      }
    } catch (Exception e) {
      // TODO: what Notes exceptions can be caught here? Should
      // we be catching exceptions within the method on a
      // per-document basis? Do we return the right response if
      // an exception occurs during the processing of the
      // document list?
      LOGGER.log(Level.SEVERE, CLASS_NAME, e);
    } finally {
      ncs.closeNotesSession(ns);
    }
    if (LOGGER.isLoggable(Level.FINER)) {
      for (int i = 0; i < authorized.size(); i++) {
        AuthorizationResponse ar = authorized.get(i);
        LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
            "AuthorizationResponse: " + ar.getDocid() + " : " + ar.isValid());
      }
    }
    return authorized;
  }

  protected String getCommonName(String NotesName) {
    if (NotesName.startsWith("cn=")) {
      int index = NotesName.indexOf('/');
      if (index > 0)
        return NotesName.substring(3, index);
    }
    return null;
  }

  protected boolean checkDocumentReaders(String NotesName,
      Vector<String> UserGroups, NotesDocument crawldoc,
      NotesDocument dbdoc) throws RepositoryException {
    final String METHOD = "checkDocumentReaders";
    LOGGER.entering(CLASS_NAME, METHOD);

    Vector<?> AllowAuthors =
        crawldoc.getItemValue(NCCONST.NCITM_DOCAUTHORREADERS);
    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
        "Document reader list is " + AllowAuthors);

    // Check using the Notes name
    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
        "Checking document level access for: " +  NotesName);
    if  (AllowAuthors.contains(NotesName)) {
       LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
           "ALLOWED: User is in authors " + NotesName);
       LOGGER.exiting(CLASS_NAME, METHOD);
       return true;
    }

    // Check using the common name
    String CommonName = getCommonName(NotesName);
    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
        "Checking document level access for user: " +  CommonName);
    if (null != CommonName) {
      if  (AllowAuthors.contains(CommonName)) {
        LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
            "ALLOWED: User is in authors " + CommonName);
        LOGGER.exiting(CLASS_NAME, METHOD);
        return true;
      }
    }

    // Check using groups
    for (int i = 0; i < UserGroups.size(); i++) {
      String group = UserGroups.elementAt(i).toString();
      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
          "Checking document level access for group: " + group);
      if (AllowAuthors.contains(group)) {
        LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
            "ALLOWED: Group is in authors " + group);
        LOGGER.exiting(CLASS_NAME, METHOD);
        return true;
      }
    }

    // Expand roles and check using roles
    Vector<String> Roles = expandRoles(NotesName, UserGroups, dbdoc);
    for (int i = 0; i < Roles.size(); i++) {
      String role = Roles.elementAt(i).toString();
      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
          "Checking document level access for role: " + role);
      if (AllowAuthors.contains(role)) {
        LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
            "ALLOWED: Role is in authors " + role);
        LOGGER.exiting(CLASS_NAME, METHOD);
        return true;
      }
    }
    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
        "DENIED: User's security principles are not in document access lists.");
    LOGGER.exiting(CLASS_NAME, METHOD);
    return false;
  }

  // Testing with R8.5 roles do not expand to nested groups.
  // You must be a direct member of the group to get the role
  // TODO: Check and validate this with other versions
  @SuppressWarnings("unchecked")
  protected Vector <String> expandRoles(String NotesName,
      Vector<String> UserGroups, NotesDocument dbdoc)
      throws RepositoryException {
    final String METHOD = "expandRoles";
    LOGGER.entering(CLASS_NAME, METHOD);

    // Do we have any roles?

    Vector<?> dbroles = dbdoc.getItemValue(NCCONST.NCITM_ROLEPREFIX);
    Vector<String> enabledRoles = new Vector<String>();
    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD, "Checking roles for user: " +
        NotesName + " using: " + dbroles.toString());
    if (dbroles.size() < 1) {
      return enabledRoles;
    }

    Vector<String> credentials = (Vector<String>) UserGroups.clone();
    credentials.add(NotesName);
    credentials.add(getCommonName(NotesName));
    StringBuffer searchstring = new StringBuffer(512);

    for (int i = 0; i < dbroles.size(); i++) {
      String roledata = dbroles.elementAt(i).toString();
      for (int j = 0; j < credentials.size(); j++) {
        searchstring.setLength(0);
        searchstring.append('~');
        searchstring.append(credentials.elementAt(j));
        searchstring.append('~');
        if (roledata.contains(searchstring)) {
            enabledRoles.add(
                roledata.substring(0, roledata.indexOf(']')+1).toLowerCase());
        }
      }
    }
    LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD, "Roles enabled for user: " +
        NotesName + " are: " + enabledRoles.toString());
    LOGGER.exiting(CLASS_NAME, METHOD);
    return enabledRoles;
  }

  protected boolean checkDatabaseAccess(String NotesName,
      NotesDocument DbDoc, Vector<?> UserGroups)
      throws RepositoryException {
    final String METHOD = "checkDatabaseAccess";
    LOGGER.entering(CLASS_NAME, METHOD);

    String CommonName = getCommonName(NotesName);
    if (checkDenyUser(NotesName, DbDoc)) {
      LOGGER.exiting(CLASS_NAME, METHOD);
      return false;
    }
    if (null != CommonName) {
      if (checkDenyUser(CommonName, DbDoc)) {
        LOGGER.exiting(CLASS_NAME, METHOD);
        return false;
      }
    }
    if (checkAllowUser(NotesName, DbDoc)) {
      LOGGER.exiting(CLASS_NAME, METHOD);
      return true;
    }
    if (null != CommonName) {
      if (checkAllowUser(CommonName, DbDoc)) {
        LOGGER.exiting(CLASS_NAME, METHOD);
        return true;
      }
    }
    if (checkAllowGroup(UserGroups, DbDoc )) {
      LOGGER.exiting(CLASS_NAME, METHOD);
      return true;
    }
    LOGGER.exiting(CLASS_NAME, METHOD);
    return false;
  }

  // TODO:  the access groups may not need to be summary data. to avoid 64k
  protected boolean checkAllowGroup(Vector<?>UserGroups,
      NotesDocument dbdoc) throws RepositoryException {
    final String METHOD = "checkAllowGroup";
    LOGGER.entering(CLASS_NAME, METHOD);

    Vector<?> AllowGroups = dbdoc.getItemValue(NCCONST.NCITM_DBPERMITGROUPS);
    LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
        "Checking database ACL for allow for groups. " +
        "Allow groups are: " + AllowGroups.toString());

    for (int i = 0; i < UserGroups.size(); i++) {
      String group = UserGroups.elementAt(i).toString();
      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD, "Checking group " + group);
      if (AllowGroups.contains(group)) {
        LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
            "ALLOWED: User is allowed through group " + group);
        LOGGER.exiting(CLASS_NAME, METHOD);
        return true;
      }
    }
    LOGGER.exiting(CLASS_NAME, METHOD);
    return false;
  }

  protected boolean checkAllowUser(String userName,
      NotesDocument dbdoc) throws RepositoryException {
    final String METHOD = "checkAllowUser";
    LOGGER.entering(CLASS_NAME, METHOD);

    LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
        "Checking database ACL for allow to user");
    Vector<?> AllowList = dbdoc.getItemValue(NCCONST.NCITM_DBPERMITUSERS);
    if  (AllowList.contains("-default-")) {
      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
          "ALLOWED: Default is allowed " + userName);
      LOGGER.exiting(CLASS_NAME, METHOD);
      return true;
    }
    if  (AllowList.contains(userName)) {
      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
          "ALLOWED: User is explictly allowed " + userName);
      LOGGER.exiting(CLASS_NAME, METHOD);
      return true;
    }
    return false;
  }

  protected boolean checkDenyUser(String userName, NotesDocument dbdoc)
      throws RepositoryException {
    final String METHOD = "checkDenyUser";
    LOGGER.entering(CLASS_NAME, METHOD);

    Vector<?> DenyList = dbdoc.getItemValue(NCCONST.NCITM_DBNOACCESSUSERS);
    LOGGER.logp(Level.FINER, CLASS_NAME, METHOD,
        "Checking database ACL for explicit deny to user");
    if  (DenyList.contains(userName)) {
      LOGGER.logp(Level.FINEST, CLASS_NAME, METHOD,
          "DENIED: User is explictly denied " + userName);
      LOGGER.exiting(CLASS_NAME, METHOD);
      return true;
    }
    LOGGER.exiting(CLASS_NAME, METHOD);
    return false;
  }
}
