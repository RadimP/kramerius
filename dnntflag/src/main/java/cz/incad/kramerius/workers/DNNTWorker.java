package cz.incad.kramerius.workers;

import com.sun.jersey.api.client.*;

import antlr.StringUtils;
import cz.incad.kramerius.FedoraAccess;
import cz.incad.kramerius.FedoraNamespaces;
import cz.incad.kramerius.ObjectPidsPath;
import cz.incad.kramerius.service.MigrateSolrIndexException;
import cz.incad.kramerius.services.IterationUtils;
import cz.incad.kramerius.services.MigrationUtils;
import cz.incad.kramerius.solr.SolrFieldsMapping;
import cz.incad.kramerius.utils.IOUtils;
import cz.incad.kramerius.utils.XMLUtils;
import cz.incad.kramerius.utils.conf.KConfiguration;
import org.fedora.api.FedoraAPIM;
import org.fedora.api.RelationshipTuple;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.ws.rs.core.MediaType;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.*;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Default dnnt flag, label worker
 */
public abstract class DNNTWorker implements Runnable {

    public static final Logger LOGGER = Logger.getLogger(DNNTWorker.class.getName());

    protected FedoraAccess fedoraAccess;
    protected Client client;
    protected CyclicBarrier barrier;
    protected String parentPid;
    protected boolean addRemoveFlag;

    public DNNTWorker(FedoraAccess fedoraAccess, Client client, String parentPid, boolean addRemoveFlag) {
        this.fedoraAccess = fedoraAccess;
        this.client = client;
        this.addRemoveFlag = addRemoveFlag;
        this.parentPid = parentPid;
    }

    protected static String selectUrl() {
        String shost = KConfiguration.getInstance().getSolrHost();
        shost = shost  + (shost.endsWith("/") ? ""  : "/") + "select";
        return shost;
    }

    protected static String updateUrl() {
        String shost = KConfiguration.getInstance().getSolrHost();
        shost = shost  + (shost.endsWith("/") ? ""  : "/") + "update";
        return shost;
    }

    protected static boolean configuredUseCursor() {
        boolean useCursor = KConfiguration.getInstance().getConfiguration().getBoolean("dnnt.usecursor", true);
        LOGGER.info("Use cursor "+useCursor);
        return useCursor;
    }


    protected void sendToDest(Client client, Document batchDoc) {
        try {
            StringWriter writer = new StringWriter();
            XMLUtils.print(batchDoc, writer);
            String shost = updateUrl();
            WebResource r = client.resource(shost);
            ClientResponse resp = r.accept(MediaType.TEXT_XML).type(MediaType.TEXT_XML).entity(writer.toString(), MediaType.TEXT_XML).post(ClientResponse.class);
            if (resp.getStatus() != ClientResponse.Status.OK.getStatusCode()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                InputStream entityInputStream = resp.getEntityInputStream();
                IOUtils.copyStreams(entityInputStream, bos);
                LOGGER.log(Level.SEVERE, new String(bos.toByteArray()));
            }
        } catch (UniformInterfaceException | ClientHandlerException | IOException | TransformerException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
    }


    protected Set<String> fetchAllPids(String q) throws UnsupportedEncodingException {
        String masterQuery = URLEncoder.encode(q,"UTF-8");
        Set<String> allSet = new HashSet<>();
        if (configuredUseCursor()) {
            try {
                IterationUtils.cursorIteration(client, KConfiguration.getInstance().getSolrHost() ,masterQuery,(em, i) -> {
                    List<String> pp = MigrationUtils.findAllPids(em);
                    allSet.addAll(pp);
                }, ()->{});
            } catch (ParserConfigurationException | SAXException | IOException | InterruptedException | MigrateSolrIndexException | BrokenBarrierException e  ) {
                LOGGER.log(Level.SEVERE,e.getMessage(),e);
            }


        } else try {
            IterationUtils.queryFilterIteration(client, MigrationUtils.configuredSourceServer(), masterQuery, (em, i) -> {
                List<String> pp = MigrationUtils.findAllPids(em);
                allSet.addAll(pp);
            }, () -> {
            });
        } catch (MigrateSolrIndexException | IOException | SAXException | ParserConfigurationException | BrokenBarrierException | InterruptedException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
        }
        return allSet;
    }
    
    

    protected String rootPid(String pid) {
        // &fl=pid_path&wt=xml
        try {
            Element element = IterationUtils.executeQuery(this.client, selectUrl(), "?q="+URLEncoder.encode(SolrFieldsMapping.getInstance().getPidField() + ":\"" + pid + "\"", "UTF-8")+"&fl=root_pid&wt=xml");
            Element rootPid = XMLUtils.findElement(element, (e) -> {
            	if (e.getNodeName().equals("str") && e.getAttribute("name").equals("root_pid")) {
            		return true;
            	} else return false;
            });
            if (rootPid != null) {
            	return rootPid.getTextContent();
            } else return null;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            return null;
        }
    	
    }
    
    protected List<String> solrPidPaths(String pid)  {
        try {
            List<String> paths = new ArrayList<>();
            Element element = IterationUtils.executeQuery(this.client, selectUrl(), "?q="+URLEncoder.encode(SolrFieldsMapping.getInstance().getPidField() + ":\"" + pid + "\"", "UTF-8")+"&fl="+ SolrFieldsMapping.getInstance().getPidPathField()+"&wt=xml");
            Element pidPath = XMLUtils.findElement(element, (e) -> {
                if (e.getNodeName().equals("arr") && e.getAttribute("name").equals(SolrFieldsMapping.getInstance().getPidPathField())) {
                    return true;
                } else return false;
            });
            if (pidPath != null) {
                NodeList childNodes = pidPath.getChildNodes();
                for(int i=0, ll=childNodes.getLength();i<ll;i++) {
                    paths.add(childNodes.item(i).getTextContent().trim());
                }
            }
            return paths;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Override
    public void run() {
        try {
            LOGGER.info("Setting label thread "+Thread.currentThread().getName()+" "+this.parentPid);

            List<String> paths = solrPidPaths(this.parentPid);
            String rootPid = rootPid(this.parentPid);
            
            if (!paths.isEmpty()) {

                String q = solrChildrenQuery(paths);
                // pokud opravdu vymlatil dnnt-labels
                boolean changedFoxmlFlag = changeFOXML(this.parentPid);

                Set<String> allSet = fetchAllPids(q);
                List<String> all = new ArrayList<>(allSet);
                if (this.addRemoveFlag) {
                    LOGGER.info("Setting flag for all children for "+this.parentPid+" and number of children are "+all.size());
                } else {
                    LOGGER.info("UnSetting flag for all children for "+this.parentPid+" and number of children are "+all.size());
                }
                int batchSize = KConfiguration.getInstance().getConfiguration().getInt(".dnnt.solr.batchsize", 100);
                int numberOfBatches = all.size() / batchSize;
                if (all.size() % batchSize > 0) {
                    numberOfBatches += 1;
                }
                for(int i=0;i<numberOfBatches;i++) {
                    int start = i * batchSize;
                    List<String> sublist = all.subList(start, Math.min(start + batchSize, all.size()));
                    Document batch = createBatchForChildren(sublist, changedFoxmlFlag);
                    sendToDest(client, batch);
                }

                if (!this.addRemoveFlag) {
                    LOGGER.info("Unsetting label for parents");
                	List<String> selectedParents = new ArrayList<>();
                	for (String path : paths) {
                		if (cz.incad.kramerius.utils.StringUtils.isAnyString(path)) {
                    		List<String> collectedPids = Arrays.stream( path.split("/")).collect(Collectors.toList());
                    		ObjectPidsPath objPidPath = new ObjectPidsPath(collectedPids);
                    		if (objPidPath.getLength() > 0) {

                    			List<String> changed = Arrays.stream(objPidPath.getPathFromRootToLeaf()).map(str -> {
                        			return str.replaceAll("\\:", "\\\\:");
                        		}).collect(Collectors.toList());

                    			boolean indirectChildExist = checkParentPath(this.parentPid, rootPid, changed);
                    			if (!indirectChildExist) {
                    				List<String> parentPids = Arrays.stream(objPidPath.getPathFromRootToLeaf()).collect(Collectors.toList());
                                    Document batchForParents = createBatchForParents(parentPids);
                                    if (batchForParents != null) {
                                        sendToDest(client, createBatchForParents(parentPids));
                                    }
                    			}
                    		}
    					}
                    	if (selectedParents != null && !selectedParents.isEmpty()) {
                            Document batchForParents = createBatchForParents(selectedParents);
                            if (batchForParents != null) {
                            	LOGGER.info(String.format("Removed parents %s", selectedParents.toString()));
                            	sendToDest(client, createBatchForParents(selectedParents));
                            }
                    	}
            		}
                		
                } else {
                    List<String> parentPids = solrPidParents(this.parentPid, paths);
                    Document batchForParents = createBatchForParents(parentPids);
                    if (batchForParents != null) {
                        sendToDest(client, createBatchForParents(parentPids));
                    }
                }
                LOGGER.info("Label for  "+this.parentPid+" has been set");
            }
        } catch (IOException | ParserConfigurationException | SAXException e) {
            LOGGER.severe("DNNT Flag for  "+this.parentPid+" hasn't been set");
            LOGGER.log(Level.SEVERE,e.getMessage(),e);
        } finally {
            //commit(client);
            try {
                this.barrier.await();
            } catch (InterruptedException | BrokenBarrierException e) {
              LOGGER.log(Level.SEVERE,e.getMessage(),e);
            }
        }

    }

    public void setBarrier(CyclicBarrier barrier) {
        this.barrier = barrier;
    }

    /** Creating batch for setting (flag,label) for children */
    protected abstract  Document createBatchForChildren(List<String> sublist, boolean changedFoxmlFlag);

    /** Creating batch for setting (flag,label) for parents 
     * @param ownerPid TODO*/
    protected abstract  Document createBatchForParents(List<String> sublist);

    /** Construct Children query */
    protected  abstract String solrChildrenQuery(List<String> pidPaths);

    /** Change foxml */
    protected abstract boolean changeFOXML(String pid);

    /** Check if label or flag exists in one of indirect child */
    protected abstract boolean checkParentPath(String parentPid, String rootPid, List<String> path) throws ParserConfigurationException, SAXException, IOException;

    
    List<String> solrPidParents(String pid, List<String> pidPaths) {
        List<String> parents = new ArrayList<>();
        pidPaths.stream().forEach(onePath-> {
            List<String> list = Arrays.stream(onePath.split("/")).collect(Collectors.toList());
            int indexOf = list.indexOf(pid);
            if (indexOf > -1) {
                parents.addAll(list.subList(0, indexOf));
            }
        });
        return parents;
    }

    List<String> changeDNNTLabelInFOXML(String pid, String label) {
        FedoraAPIM apim = fedoraAccess.getAPIM();
        String dnntLabel = FedoraNamespaces.KRAMERIUS_URI+"dnnt-label";
        List<RelationshipTuple> relationships = apim.getRelationships(pid, dnntLabel);
        Optional<RelationshipTuple> any = relationships.stream().filter(tuple -> {
            return tuple.getObject().equals(label);
        }).findAny();
        if (!any.isPresent()) {
            if (addRemoveFlag) apim.addRelationship(pid, dnntLabel,label, true, null);
        } else {
            apim.purgeRelationship(pid, dnntLabel, any.get().getObject(), any.get().isIsLiteral(), any.get().getDatatype());
            if (addRemoveFlag) apim.addRelationship(pid, dnntLabel,label, true, null);
        }

        relationships = apim.getRelationships(pid, dnntLabel);
        return relationships.stream().map(RelationshipTuple::getObject).collect(Collectors.toList());
    }

    @Deprecated
    boolean changeDNNTInFOXML(String pid, boolean flag) {
        FedoraAPIM apim = fedoraAccess.getAPIM();
        String dnntFlag = FedoraNamespaces.KRAMERIUS_URI+"dnnt";
        List<RelationshipTuple> relationships = apim.getRelationships(pid, dnntFlag);
        if (relationships.isEmpty()) {
            if (flag)  apim.addRelationship(pid, dnntFlag,"true", true, null);
        } else {
            apim.purgeRelationship(pid, dnntFlag, relationships.get(0).getObject(), relationships.get(0).isIsLiteral(), relationships.get(0).getDatatype());
            if (flag) apim.addRelationship(pid, dnntFlag,"true", true, null);
        }

        relationships = apim.getRelationships(pid, dnntFlag);
        return !relationships.isEmpty();
    }

    protected boolean changeDNNTInFOXML(String pid) {
        return changeDNNTInFOXML(pid, this.addRemoveFlag);
    }
}
