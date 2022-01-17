package cz.incad.kramerius.processes;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.hazelcast.internal.json.Json;
import com.hazelcast.internal.json.JsonValue;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;
import cz.incad.kramerius.fedora.RepoModule;
import cz.incad.kramerius.fedora.om.RepositoryException;
import cz.incad.kramerius.fedora.om.impl.AkubraDOManager;
import cz.incad.kramerius.processes.starter.ProcessStarter;
import cz.incad.kramerius.processes.utils.ProcessUtils;
import cz.incad.kramerius.repository.KrameriusRepositoryApi;
import cz.incad.kramerius.repository.KrameriusRepositoryApiImpl;
import cz.incad.kramerius.repository.RepositoryApi;
import cz.incad.kramerius.resourceindex.ResourceIndexModule;
import cz.incad.kramerius.solr.SolrModule;
import cz.incad.kramerius.statistics.NullStatisticsModule;
import cz.incad.kramerius.utils.Dom4jUtils;
import cz.incad.kramerius.utils.java.Pair;
import net.sf.json.JSONObject;
import org.apache.solr.client.solrj.SolrServerException;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.Node;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.logging.Logger;

/**
 * Deklarace procesu je v shared/common/src/main/java/cz/incad/kramerius/processes/res/lp.st (set_policy)
 * Nahrazuje cz.incad.kramerius.service.impl.PolicyServiceImpl.main()
 */
public class SetPolicyProcess {

    public static final Logger LOGGER = Logger.getLogger(SetPolicyProcess.class.getName());

    public static final String API_AUTH_HEADER_AUTH_TOKEN = "process-auth-token";
    public static final String API_AUTH_HEADER_CLIENT = "client";
    public static final String API_AUTH_HEADER_UID = "uid";
    public static final String API_AUTH_HEADER_ACCESS_TOKEN = "access-token";

    /**
     * args[0] - scope (OBJECT/TREE)
     * args[1] - policy (PRIVATE/PUBLIC)
     * args[2] - pid of root object, for example "uuid:df693396-9d3f-4b3b-bf27-3be0aaa2aadf"
     * args[3-...] - optional title of the root object
     */
    public static void main(String[] args) throws IOException, SolrServerException, RepositoryException {
        //args
        /*LOGGER.info("args: " + Arrays.asList(args));
        for (String arg : args) {
            System.out.println(arg);
        }*/
        int argsIndex = 0;
        String authToken = args[argsIndex++]; //auth token always first, but still suboptimal solution, best would be if it was outside the scope of this as if ProcessHelper.scheduleProcess() similarly to changing name (ProcessStarter)
        //Kramerius
        String krameriusApiAuthClient = args[argsIndex++];
        String krameriusApiAuthUid = args[argsIndex++];
        String krameriusApiAuthAccessToken = args[argsIndex++];
        //process params
        Scope scope = Scope.valueOf(args[argsIndex++]);
        Policy policy = Policy.valueOf(args[argsIndex++]);
        String pid = args[argsIndex++];
        String title = mergeArraysEnd(args, argsIndex);
        String scopeDesc = scope == Scope.OBJECT ? "jen objekt" : "objekt včetně potomků";
        if (title != null) {
            ProcessStarter.updateName(String.format("Změna viditelnosti %s (%s, %s, %s)", title, pid, policy, scopeDesc));
        } else {
            ProcessStarter.updateName(String.format("Změna viditelnosti %s (%s, %s)", pid, policy, scopeDesc));
        }
        Injector injector = Guice.createInjector(new SolrModule(), new ResourceIndexModule(), new RepoModule(), new NullStatisticsModule());
        KrameriusRepositoryApi repository = injector.getInstance(Key.get(KrameriusRepositoryApiImpl.class)); //FIXME: hardcoded implementation

        //check object exists in repository
        if (!repository.getLowLevelApi().objectExists(pid)) {
            throw new RuntimeException(String.format("object %s not found in repository", pid));
        }
        boolean includingDescendants = scope == Scope.TREE;
        boolean noErrors = setPolicy(policy, pid, includingDescendants, repository);
        scheduleReindexation(authToken, pid, title, includingDescendants, krameriusApiAuthClient, krameriusApiAuthUid, krameriusApiAuthAccessToken);
        if (!noErrors) {
            throw new WarningException("failed to set policy for some objects");
        }
    }

    /**
     * @return true if all objects where processed without problems, false otherwise
     */
    private static boolean setPolicy(Policy policy, String pid, boolean includingDescendants, KrameriusRepositoryApi repository) {
        LOGGER.info(String.format("Setting policy (to %s) for %s", policy, pid));
        Lock writeLock = AkubraDOManager.getWriteLock(pid);
        try {
            setPolicyDC(pid, policy, repository); //TODO: zatim takto, at nevznikaji zmatky, dokud neni datastream DC uplne odstranen
            setPolicyRELS_EXT(pid, policy, repository);
            //setPolicyPOLICY(pid, policy, repository); //tohle uz vubec neresit, datastream POLICY je prebytecny a ne vzdy pritomen

            boolean noErros = true;
            if (includingDescendants) {
                Pair<List<RepositoryApi.Triplet>, List<RepositoryApi.Triplet>> children = repository.getChildren(pid);
                if (children.getFirst() != null && !children.getFirst().isEmpty()) {
                    for (RepositoryApi.Triplet triplet : children.getFirst()) {
                        String childPid = triplet.target;
                        noErros &= setPolicy(policy, childPid, includingDescendants, repository);
                    }
                }
            }
            return noErros;
        } catch (Exception ex) {
            LOGGER.warning("Cannot set policy for object " + pid + ", skipping ");
            ex.printStackTrace();
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    private static void setPolicyRELS_EXT(String pid, Policy policy, KrameriusRepositoryApi repository) throws RepositoryException, IOException {
        if (!repository.isRelsExtAvailable(pid)) {
            throw new RepositoryException("RDF record (datastream RELS-EXT) not found for " + pid);
        }
        Document relsExt = repository.getRelsExt(pid, true);
        Element rootEl = (Element) Dom4jUtils.buildXpath("/rdf:RDF/rdf:Description").selectSingleNode(relsExt);
        List<Node> policyEls = Dom4jUtils.buildXpath("rel:policy").selectNodes(rootEl);
        for (Node policyEl : policyEls) {
            String content = policyEl.getText();
            if (content.startsWith("policy:")) {
                policyEl.detach();
            }
        }
        Element newPolicyEl = rootEl.addElement("policy", Dom4jUtils.getNamespaceUri("rel"));
        newPolicyEl.addText(policy == Policy.PRIVATE ? "policy:private" : "policy:public");
        repository.updateRelsExt(pid, relsExt);
    }

    private static void setPolicyDC(String pid, Policy policy, KrameriusRepositoryApi repository) throws RepositoryException, IOException {
        if (!repository.isDublinCoreAvailable(pid)) {
            LOGGER.info("Dublin Core record (datastream DC) not found for " + pid);
            return;
        }
        Document dc = repository.getDublinCore(pid, true);
        Element rootEl = (Element) Dom4jUtils.buildXpath("//oai_dc:dc").selectSingleNode(dc);
        List<Node> policyEls = Dom4jUtils.buildXpath("dc:rights").selectNodes(rootEl);
        for (Node policyEl : policyEls) { //da se cekat, ze budou v datech duplikovne informace
            String content = policyEl.getText();
            if (content.startsWith("policy:")) {
                policyEl.detach();
            }
        }
        Element newRightsEl = rootEl.addElement("rights", Dom4jUtils.getNamespaceUri("dc"));
        newRightsEl.addText(policy == Policy.PRIVATE ? "policy:private" : "policy:public");
        repository.updateDublinCore(pid, dc);
    }

    private static void scheduleReindexation(String authToken, String pid, String title, boolean includingDescendants, String krameriusApiAuthClient, String krameriusApiAuthUid, String krameriusApiAuthAccessToken) {
        Client client = Client.create();
        WebResource resource = client.resource(ProcessUtils.getNewAdminApiEndpoint() + "/processes");
        JSONObject data = new JSONObject();
        data.put("defid", "new_indexer_index_object");
        JSONObject params = new JSONObject();
        params.put("type", includingDescendants ? "TREE_AND_FOSTER_TREES" : "OBJECT");
        params.put("pid", pid);
        params.put("title", title);
        params.put("ignoreInconsistentObjects", true);
        data.put("params", params);

        try {
            String response = resource
                    .accept(MediaType.APPLICATION_JSON)
                    .type(MediaType.APPLICATION_JSON)
                    .header(API_AUTH_HEADER_AUTH_TOKEN, authToken)
                    .header(API_AUTH_HEADER_CLIENT, krameriusApiAuthClient)
                    .header(API_AUTH_HEADER_UID, krameriusApiAuthUid)
                    .header(API_AUTH_HEADER_ACCESS_TOKEN, krameriusApiAuthAccessToken)
                    .entity(data.toString(), MediaType.APPLICATION_JSON)
                    .post(String.class);
            //System.out.println("response: " + response);
        } catch (UniformInterfaceException e) {
            ClientResponse errorResponse = e.getResponse();
            String responseBody = errorResponse.getEntity(String.class);
            String bodyToPrint = responseBody;
            if (responseBody != null) {
                try {
                    JsonValue jsonBody = Json.parse(responseBody);
                    bodyToPrint = jsonBody.asString();
                } catch (Throwable pe) {
                    //not JSON
                }
            }
            throw new RuntimeException(errorResponse.toString() + ": " + bodyToPrint, e);
        }
    }

    //["Quartet A minor", " op. 51", " no. 2. Andante moderato"] => "Quartet A minor, op. 51, no. 2 Andante moderato"
    private static String mergeArraysEnd(String[] args, int argsIndex) {
        String result = "";
        for (int i = argsIndex; i < args.length; i++) {
            String arg = args[i];
            if (arg != null && !"null".equals(arg)) {
                result += args[i];
                if (i != args.length - 1) {
                    result += ",";
                }
            }
        }
        result = result.trim();
        return result.isEmpty() ? null : result;
    }

    public enum Scope {
        OBJECT, TREE
    }

    public enum Policy {
        PRIVATE, PUBLIC
    }
}
