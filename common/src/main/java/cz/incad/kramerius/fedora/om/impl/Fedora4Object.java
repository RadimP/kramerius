/*
 * Copyright (C) 2016 Pavel Stastny
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package cz.incad.kramerius.fedora.om.impl;

import static cz.incad.kramerius.fedora.utils.Fedora4Utils.*;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.xml.messaging.saaj.util.ByteOutputStream;
import cz.incad.kramerius.FedoraNamespaces;
import cz.incad.kramerius.fedora.om.NotFoundInRepositoryException;
import cz.incad.kramerius.fedora.om.RepositoryException;
import cz.incad.kramerius.fedora.om.RepositoryObject;
import cz.incad.kramerius.fedora.om.RepositoryDatastream;
import cz.incad.kramerius.fedora.utils.Fedora4Utils;
import cz.incad.kramerius.resourceindex.ProcessingIndexFeeder;
import cz.incad.kramerius.utils.FedoraUtils;
import cz.incad.kramerius.utils.StringUtils;
import cz.incad.kramerius.utils.XMLUtils;
import cz.incad.kramerius.utils.pid.PIDParser;
import org.antlr.stringtemplate.StringTemplate;
import org.antlr.stringtemplate.StringTemplateGroup;
import org.antlr.stringtemplate.language.DefaultTemplateLexer;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.solr.client.solrj.SolrServerException;
import org.fcrepo.client.*;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

/**
 * @author pavels
 *
 */
public class Fedora4Object implements RepositoryObject {

    public static final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public  static final Logger LOGGER = Logger.getLogger(Fedora4Object.class.getName());
    public static final String RDF_DESCRIPTION_ELEMENT = "Description";
    public static final String RDF_CONTAINS_ELEMENT = "contains";
    public static final String RDF_TYPE_ELEMENT = "type";
    public static final String RDF_ELEMENT = "RDF";

    private List<String> path;
    private FcrepoClient client;
    private Fedora4Repository repo;
    private String pid;
    private ProcessingIndexFeeder feeder;

    private String transactionId;


    public Fedora4Object(Fedora4Repository repo, FcrepoClient client, List<String> path, String pid, ProcessingIndexFeeder feeder) {
        super();
        this.client = client;
        this.path = path;
        this.repo = repo;
        this.pid = pid;
        this.feeder = feeder;
    }


    public String getPid() {
        return pid;
    }


    @Override
    public String getPath() {
        return Fedora4Utils.path(this.path);
    }



    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    @Override
    public RepositoryDatastream createRedirectedStream(String streamId, String url) throws RepositoryException {
        URI childUri = URI.create(objectPath() + "/" + streamId);
        try (FcrepoResponse response = client.put(childUri).body(new ByteArrayInputStream("".getBytes()), "message/external-body; access-type=URL; URL=\""+url+"\"").perform()) {
            Fedora4Datastream ds = new Fedora4Datastream(this.repo,this.client, new ArrayList<String>(this.path) {{
                add(streamId);
            }},streamId, Fedora4Datastream.Type.INDIRECT);
            if (this.transactionId != null) {
                ds.setTransactionId(this.transactionId);
            }
            return ds;
        } catch (FcrepoOperationFailedException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }
    }

    private String objectPath() {
        if (this.transactionId != null) {
            return endpoint() + (endpoint().endsWith("/") ? "" : "/") + this.transactionId +  Fedora4Utils.path(this.path);
        } else {
            return endpoint()  + Fedora4Utils.path(this.path);
        }
    }


    @Override
    public List<RepositoryDatastream> getStreams() throws RepositoryException {
        List<RepositoryDatastream> list = new ArrayList<>();
        Document metadata = getMetadata();
        List<Element> elms =  XMLUtils.getElementsRecursive(metadata.getDocumentElement(),(element) -> {
            if (element.getLocalName().equals("contains") && element.getNamespaceURI().equals("http://www.w3.org/ns/ldp#")) {
                return true;
            } else return false;
        });

        for (Element elm : elms) {
            String resource = elm.getAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
            List<String> path = Fedora4Utils.link(resource);
            Fedora4Datastream ds = new Fedora4Datastream(this.repo, this.client,path,path.get(path.size()-1), Fedora4Datastream.Type.DIRECT);
            if (this.transactionId != null) {
                ds.setTransactionId(this.transactionId);
            }
            list.add(ds);
        }
        return list;
    }

    @Override
    public void deleteStream(String streamId) throws RepositoryException {
        try (FcrepoResponse response = new DeleteBuilder(URI.create(this.objectPath()+"/"+streamId), client).perform()) {
            if (response.getStatusCode() == 204) {
                try (FcrepoResponse thombStoneResponse = new DeleteBuilder(URI.create(objectPath()+"/"+streamId+"/fcr:tombstone"), client).perform()) {
                    if (thombStoneResponse.getStatusCode() != 204) {
                        throw new RepositoryException("Cannot delete tombstone for streamId "+streamId);
                    }
                }
            }  else {
                throw new RepositoryException("Cannot delete  streamId "+streamId);
            }
        } catch (FcrepoOperationFailedException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }

    }

    @Override
    public RepositoryDatastream createStream(String streamId, String mimeType, InputStream input) throws RepositoryException {
        try {
            ByteOutputStream bos = new ByteOutputStream();
            int length = IOUtils.copy(input, bos);

            URI childUri = URI.create(this.objectPath()+"/"+streamId);
            if (streamId.equals("RELS-EXT")) {
                mimeType = "text/xml";
            }

            Fedora4Datastream ds = new Fedora4Datastream(this.repo,this.client, new ArrayList<String>(this.path) {{
                add(streamId);
            }}, streamId, Fedora4Datastream.Type.DIRECT);
            if (this.transactionId != null) {
                ds.setTransactionId(transactionId);
            }

            if (!repo.exists(childUri)) {
                try (FcrepoResponse response = new PutBuilder(childUri, client).body(new ByteArrayInputStream(Arrays.copyOf(bos.getBytes(), length)), mimeType).perform()) {
                    if (response.getStatusCode() == 201) {
                        URI location = response.getLocation();
                        if (streamId.equals(FedoraUtils.RELS_EXT_STREAM)) {
                            // process rels-ext and create all children and relations

                            this.feeder.deleteByRelationsForPid(pid);
                            RELSEXTSPARQLBuilder sparqlBuilder = new RELSEXTSPARQLBuilderImpl();
                            long startProcessing = System.currentTimeMillis();
                            // index after callect
                            String sparql = sparqlBuilder.sparqlProps(new String(Arrays.copyOf(bos.getBytes(), length), "UTF-8").trim(), (object, localName)->{

                                if(localName.equals("hasModel")) {
                                    try {

                                        if (this.streamExists(FedoraUtils.DC_STREAM)) {

                                            try {
                                                InputStream stream = this.getStream(FedoraUtils.DC_STREAM).getContent();
                                                Element title = XMLUtils.findElement(XMLUtils.parseDocument(stream, true).getDocumentElement(), "title", FedoraNamespaces.DC_NAMESPACE_URI);
                                                if (title != null) {
                                                    this.indexDescription(object,title.getTextContent());
                                                } else {
                                                    this.indexDescription(object,"");
                                                }
                                            } catch (ParserConfigurationException e) {
                                                LOGGER.log(Level.SEVERE,e.getMessage(),e);
                                                this.indexDescription(object,"");
                                            } catch (SAXException e) {
                                                LOGGER.log(Level.SEVERE,e.getMessage(),e);
                                                this.indexDescription(object,"");
                                            }
                                        }
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    } catch (SolrServerException e) {
                                        throw new RuntimeException(e);
                                    }
                                } else {
                                    try {
                                        this.indexRelation(localName, object);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    } catch (SolrServerException e) {
                                        throw new RuntimeException(e);
                                    }
                                }

                                RepositoryObject created = repo.createOrFindObject(object);
                                return "/"+this.repo.getBoundContext()+created.getPath();
                            });

                            // spread properties from relsext
                            this.updateSPARQL(sparql);
                            ds.updateSPARQL(Fedora4Repository.UPDATE_INDEXING_SPARQL());
                        }
                    } else {
                        throw new RepositoryException("Cannot create  stream "+streamId);
                    }
                    return ds;
                } catch (FcrepoOperationFailedException e) {
                    throw new RepositoryException(e);
                } catch (SAXException e) {
                    throw new RepositoryException(e);
                } catch (ParserConfigurationException e) {
                    throw new RepositoryException(e);
                } catch (SolrServerException e) {
                    throw new RepositoryException(e);
                }
            } else {
                throw new RepositoryException("stream '"+streamId+"' already objectExists");
            }
        } catch (IOException e) {
            throw new RepositoryException(e);
        }
    }

    private void indexRelation(String localName, String object) throws IOException, SolrServerException {
        this.feeder.feedRelationDocument( this.getPid(), localName, object);
    }

    private void indexDescription(String model, String dctitle) throws IOException, SolrServerException {
        List<String> paths = Fedora4Utils.normalizePath(this.getPid());
        String link = endpoint()+Fedora4Utils.path(paths);

        this.feeder.deleteDescriptionByPid(this.getPid());
        this.feeder.feedDescriptionDocument(this.getPid(), model, dctitle, link);
    }

    public void deleteProcessingIndex() throws IOException, SolrServerException {
        feeder.deleteByPid(this.getPid());
    }


    @Override
    public boolean streamExists(String streamId) throws RepositoryException {
        URI childUri = URI.create(objectPath() + "/" + streamId);
        return repo.exists(childUri);
    }

    @Override
    public RepositoryDatastream getStream(String streamId) throws RepositoryException {
        URI childUri = URI.create(objectPath()+ "/" + streamId);
        Fedora4Datastream ds = new Fedora4Datastream(this.repo,this.client, new ArrayList<String>(this.path) {{
            add(streamId);
        }}, streamId, Fedora4Datastream.Type.DIRECT);
        if (this.transactionId != null) {
            ds.setTransactionId(this.transactionId);
        }
        return ds;
    }

    @Override
    public void updateSPARQL(String sparql) throws RepositoryException {
        URI updatingPath = URI.create(this.objectPath());
        try (FcrepoResponse streamResp = new PatchBuilder(updatingPath, client).body(new ByteArrayInputStream(sparql.getBytes("UTF-8"))).perform()) {
            if (streamResp.getStatusCode() != 204) {
                String s = IOUtils.toString(streamResp.getBody(), "UTF-8");
                throw new RepositoryException("Cannot update properties for  stream "+this.path+" due to "+s);
            }
        } catch (FcrepoOperationFailedException e) {
            throw new RepositoryException(e);
        } catch (UnsupportedEncodingException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }
    }

    @Override
    public Date getLastModified() throws RepositoryException {
        URI uri = URI.create(this.objectPath() + "/fcr:metadata");
        try (FcrepoResponse response = client.get(uri).accept("application/rdf+xml").perform()) {
            if (response.getStatusCode() == 200) {
                InputStream body = response.getBody();
                return extractDate(body, "lastModified", FedoraNamespaces.FEDORA_4_NAMESPACE_URI);
            } else if (response.getStatusCode() == 404) {
                throw new NotFoundInRepositoryException("cannot find link "+uri);
            } else {
                throw new RepositoryException("communication error :"+response.getStatusCode()+"  "+IOUtils.toString(response.getBody(),"UTF-8"));
            }
        } catch (FcrepoOperationFailedException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (ParseException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }
    }

    @Override
    public Document getMetadata() throws RepositoryException {
        String link = objectPath();
        return getMetadataByLink(link);
    }

    private Document getMetadataByLink(String link) throws RepositoryException {
        URI uri = URI.create(link + "/fcr:metadata");
        try (FcrepoResponse response = client.get(uri).accept("application/rdf+xml").perform()) {
            if (response.getStatusCode() == 200) {
                InputStream body = response.getBody();
                return XMLUtils.parseDocument(body, true);
            } else if (response.getStatusCode() == 404) {
                throw new NotFoundInRepositoryException("cannot find link "+uri);
            } else {
                throw new RepositoryException("communication error :"+response.getStatusCode()+"  "+IOUtils.toString(response.getBody(),"UTF-8"));
            }
        } catch (FcrepoOperationFailedException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }


    }


    @Override
    public InputStream getFoxml() throws RepositoryException {
        try {
            InputStream stream = this.getClass().getResourceAsStream("res/foxml.stg");
            String string = IOUtils.toString(stream, Charset.forName("UTF-8"));
            StringTemplateGroup tmplGroup = new StringTemplateGroup(new StringReader(string), DefaultTemplateLexer.class);
            StringTemplate foxml = tmplGroup.getInstanceOf("FOXML");

            List<Map<String,String>> foxmlStreams = new ArrayList<>();
            List<RepositoryDatastream> dataStreams = this.getStreams().stream().filter(s-> {
                try {
                    return !s.getName().equals(FedoraUtils.POLICY_STREAM) && !s.getName().equals(FedoraUtils.RELS_EXT_STREAM);
                } catch(RepositoryException e) {
                    LOGGER.log(Level.SEVERE,e.getMessage());
                    return false;
                }
            }).collect(Collectors.toList());

            Map<String, String> relsExt = new HashMap<>();
            relsExt.put("id", FedoraUtils.RELS_EXT_STREAM);
            relsExt.put("mimetype",this.getStream(FedoraUtils.RELS_EXT_STREAM).getMimeType());
            // must be used RELS-EXT
            Document metadata = XMLUtils.parseDocument(this.getStream(FedoraUtils.RELS_EXT_STREAM).getContent(), true);


            StringWriter stringWriter = new StringWriter();
            XMLUtils.print(metadata, stringWriter);
            String xml = this.removeXmlInstruction(stringWriter.toString());
            relsExt.put("data",xml);
            relsExt.put("date",SIMPLE_DATE_FORMAT.format(this.getStream(FedoraUtils.RELS_EXT_STREAM).getLastModified()));
            relsExt.put("templateName","xmlcontent");


            for (RepositoryDatastream dataStream :  dataStreams) {
                String mimeType = dataStream.getMimeType();

                Map<String, String> foxmlStream = new HashMap<>();
                foxmlStream.put("id",dataStream.getName());
                foxmlStream.put("mimetype",dataStream.getMimeType());
                foxmlStream.put("date", SIMPLE_DATE_FORMAT.format(dataStream.getLastModified()));

                LOGGER.info("processing stream "+dataStream.getName());

                if (mimeType.equals("text/xml") || mimeType.equals("application/xml")) {
                    InputStream content = dataStream.getContent();
                    BOMInputStream bomIn = new BOMInputStream(content);
                    String rawData = IOUtils.toString(bomIn, "UTF-8");
                    stringWriter = new StringWriter();
                    Document doc = XMLUtils.parseDocument(new StringReader(rawData.trim()), true);
                    XMLUtils.print(doc, stringWriter);
                    xml = this.removeXmlInstruction(stringWriter.toString());
                    foxmlStream.put("data", xml);
                    foxmlStream.put("templateName","xmlcontent");

                } else if (mimeType.startsWith("message/external-body")) {
                    String[] parts = mimeType.split(";");
                    for (String part :
                            parts) {
                        if (part.startsWith("url")) {
                            String[] values = part.split("=");
                            if (values .length >= 2) {
                                foxmlStream.put("data",values[1]);
                            }
                        }
                    }
                    foxmlStream.put("templateName","redirectcontent");

                } else {
                    byte[] bytes = IOUtils.toByteArray(dataStream.getContent());

                    String data = Base64.getEncoder().encodeToString(bytes);
                    foxmlStream.put("data",data);
                    foxmlStream.put("templateName","binary");
                }

                foxmlStreams.add(foxmlStream);
            }

            foxmlStreams.add(relsExt);

            foxml.setAttribute("date",SIMPLE_DATE_FORMAT.format(this.getLastModified()));
            foxml.setAttribute("pid",this.pid);
            foxml.setAttribute("streams",foxmlStreams);

            return new ByteArrayInputStream(foxml.toString().getBytes("UTF-8"));

        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (TransformerException e) {
            throw new RepositoryException(e);
        }
    }

    private String removeXmlInstruction(String readAsString) {
        if (readAsString.trim().startsWith("<?")) {
            int endIndex = readAsString.indexOf("?>");
            return readAsString.substring(endIndex+2);
        } else return readAsString;
    }


    @Override
    public void addRelation(String relation, String namespace, String targetRelation) throws RepositoryException {
        try {
            RepositoryDatastream stream = this.getStream(FedoraUtils.RELS_EXT_STREAM);
            Document document = XMLUtils.parseDocument(stream.getContent(), true);
            Element rdfDesc = XMLUtils.findElement(document.getDocumentElement(), RDF_DESCRIPTION_ELEMENT, FedoraNamespaces.RDF_NAMESPACE_URI);
            Element subElm = document.createElementNS(namespace,relation);
            subElm.setAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "rdf:resource", PIDParser.INFO_FEDORA_PREFIX+targetRelation);
            rdfDesc.appendChild(subElm);
            changeRelations(document);
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (TransformerException e) {
            throw new RepositoryException(e);
        }
    }

    private void changeRelations(Document document) throws TransformerException, RepositoryException {
        StringWriter stringWriter = new StringWriter();
        XMLUtils.print(document,stringWriter);

        this.deleteStream(FedoraUtils.RELS_EXT_STREAM);
        this.createStream(FedoraUtils.RELS_EXT_STREAM, "text/xml", new ByteArrayInputStream(stringWriter.toString().getBytes(Charset.forName("UTF-8"))));
    }

    @Override
    public void addLiteral(String relation, String namespace, String value) throws RepositoryException {
        // only  RELS_EXT
        try {
            RepositoryDatastream stream = this.getStream(FedoraUtils.RELS_EXT_STREAM);
            Document document = XMLUtils.parseDocument(stream.getContent(), true);
            Element rdfDesc = XMLUtils.findElement(document.getDocumentElement(), RDF_DESCRIPTION_ELEMENT, FedoraNamespaces.RDF_NAMESPACE_URI);
            Element subElm = document.createElementNS(namespace, relation);
            subElm.setTextContent(value);
            rdfDesc.appendChild(subElm);
            changeRelations(document);
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (TransformerException e) {
            throw new RepositoryException(e);
        }
    }

    @Override
    public void removeRelation(String relation, String namespace, String targetRelation) throws RepositoryException {
        try {
            if (this.transactionId == null) {
                LOGGER.warning("Operation is not in transaction; it could end with inconsistant state");
            }
            final String targetPID = targetRelation.startsWith(PIDParser.INFO_FEDORA_PREFIX) ?  targetRelation : PIDParser.INFO_FEDORA_PREFIX+targetRelation;
            RepositoryDatastream stream = this.getStream(FedoraUtils.RELS_EXT_STREAM);
            Document document = XMLUtils.parseDocument(stream.getContent(), true);
            Element relationElement = XMLUtils.findElement(document.getDocumentElement(), (element)-> {
                String elmNamespace = element.getNamespaceURI();
                String elmLocalname = element.getLocalName();
                String elmResourceAttribute = element.getAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                return (elmNamespace.equals(namespace)) && (elmLocalname.equals(relation)) && elmResourceAttribute.equals(targetPID);
            });

            if (relationElement != null) {
                relationElement.getParentNode().removeChild(relationElement);
                changeRelations(document);
            } else {
                LOGGER.warning("Cannot find relation '"+namespace+relation);
            }

            //String path = getFullPath(targetRelation);

            this.updateSPARQL(Fedora4Repository.DELETE_RELATION(relation,namespace,this.repo.getObject(targetRelation).getFullPath()));

        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (TransformerException e) {
            throw new RepositoryException(e);
        }
    }

    public String getFullPath() throws RepositoryException {
        return this.transactionId != null ? "/"+ this.repo.getBoundContext()+"/"+ this.transactionId+this.getPath(): "/"+ this.repo.getBoundContext()+this.getPath();
    }



    public List<Triple<String, String, String>> getRelations(String namespace) throws RepositoryException {
        try {
            Document metadata = XMLUtils.parseDocument(getStream(FedoraUtils.RELS_EXT_STREAM).getContent(), true);
            List<Triple<String, String, String>> retvals = XMLUtils.getElementsRecursive(metadata.getDocumentElement(), (element) -> {
                String elmNamespace = element.getNamespaceURI();
                if (namespace != null) {
                    return namespace.equals(elmNamespace) && element.hasAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                } else {
                    return element.hasAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                }
            }).stream().map((elm) -> {
                String resource = elm.getAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                if (resource.startsWith(PIDParser.INFO_FEDORA_PREFIX)) {
                    resource = resource.substring(PIDParser.INFO_FEDORA_PREFIX.length());
                }

                Triple<String, String, String> triple = new ImmutableTriple<>(elm.getNamespaceURI(), elm.getLocalName(), resource);
                return triple;
            }).collect(Collectors.toList());
            Collections.reverse(retvals);
            return retvals;
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }
    }


    public  List<Triple<String, String, String>> getLiterals(String namespace) throws RepositoryException {
        try {
            Document metadata = XMLUtils.parseDocument(getStream(FedoraUtils.RELS_EXT_STREAM).getContent(), true);

            List<Triple<String, String, String>> retvals = XMLUtils.getElementsRecursive(metadata.getDocumentElement(), (element) -> {
                String elmNamespace = element.getNamespaceURI();
                if (namespace != null) {
                    return namespace.equals(elmNamespace) && !element.hasAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource") && StringUtils.isAnyString(element.getTextContent());
                } else {
                    return !element.hasAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource") && StringUtils.isAnyString(element.getTextContent());
                }
            }).stream().filter((elm)-> {
                return !elm.getLocalName().equals(RDF_ELEMENT) && !elm.getLocalName().equals(RDF_DESCRIPTION_ELEMENT);
            }).map((elm) -> {
                String content = elm.getTextContent();
                Triple<String, String, String> triple = new ImmutableTriple<>(elm.getNamespaceURI(), elm.getLocalName(), content);
                return triple;
            }).collect(Collectors.toList());

            Collections.reverse(retvals);
            return retvals;
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }
    }

    @Override
    public boolean relationExists(String relation, String namespace, String targetRelation) throws RepositoryException {
        if (targetRelation.startsWith(PIDParser.INFO_FEDORA_PREFIX)) {
            targetRelation = targetRelation.substring(PIDParser.INFO_FEDORA_PREFIX.length());
        }
        List<String> strings = Fedora4Utils.normalizePath(targetRelation);
        String postfix = Fedora4Utils.BOUND_CONTEXT + strings.stream().reduce("", (a,b)-> {return a+"/"+b;});

        Document metadata = getMetadata();
        Element foundElement = XMLUtils.findElement(metadata.getDocumentElement(), (element) -> {
            String elmNamespace = element.getNamespaceURI();
            String elmName = element.getLocalName();
            if (elmName.equals(relation) && namespace.equals(elmNamespace)) {
                String resource = element.getAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                return resource.endsWith(postfix);
            }
            return false;
        });
        return foundElement != null;
    }

    @Override
    public boolean literalExists(String relation, String namespace, String value) throws RepositoryException {
        try {
            Document metadata = XMLUtils.parseDocument(getStream(FedoraUtils.RELS_EXT_STREAM).getContent(), true);
            Element foundElement = XMLUtils.findElement(metadata.getDocumentElement(), (element) -> {
                String elmNamespace = element.getNamespaceURI();
                String elmName = element.getLocalName();
                if (elmName.equals(relation) && namespace.equals(elmNamespace)) {
                    String cont = element.getTextContent();
                    return cont.endsWith(value);
                }
                return false;
            });
            return foundElement != null;
        } catch (RepositoryException e) {
            throw new RepositoryException(e);
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        }
    }

    @Override
    public void removeLiteral(String relation, String namespace, String value) throws RepositoryException {
        try {
            RepositoryDatastream stream = this.getStream(FedoraUtils.RELS_EXT_STREAM);
            Document document = XMLUtils.parseDocument(stream.getContent(), true);
            Element relationElement = XMLUtils.findElement(document.getDocumentElement(), (element)-> {
                String elmNamespace = element.getNamespaceURI();
                String elmLocalname = element.getLocalName();
                String elmResourceAttribute = element.getTextContent();
                return (elmNamespace.equals(namespace)) && (elmLocalname.equals(relation)) && elmResourceAttribute.equals(value);
            });

            if (relationElement != null) {
                relationElement.getParentNode().removeChild(relationElement);
                changeRelations(document);
                this.updateSPARQL(Fedora4Repository.DELETE_LITERAL(relation,namespace,value));
            } else {
                LOGGER.warning("Cannot find literal '"+namespace+relation);
            }
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (TransformerException e) {
            throw new RepositoryException(e);
        }
    }

    @Override
    public void removeRelationsByTarget(String target) throws RepositoryException {
        try {
            RepositoryDatastream stream = this.getStream(FedoraUtils.RELS_EXT_STREAM);
            Document document = XMLUtils.parseDocument(stream.getContent(), true);
            List<Element> elms = XMLUtils.getElementsRecursive(document.getDocumentElement(), (element) -> {
                String resource = element.getAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                return (resource.equals(target));
            });
            if (!elms.isEmpty()) {
                removeRelations(document, elms);
                changeRelations(document);
            }
            Document metadata = this.getMetadata();
            List<Element> metadataElms = XMLUtils.getElementsRecursive(metadata.getDocumentElement(), (element) -> {
                String resource = element.getAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                return (resource.equals(target));
            });
            if (!metadataElms.isEmpty()) {
                removeRelations(document, metadataElms);
            }

        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (TransformerException e) {
            throw new RepositoryException(e);
        }

    }

    @Override
    public void removeRelationsByNamespace(String namespace) throws RepositoryException {
        try {
            RepositoryDatastream stream = this.getStream(FedoraUtils.RELS_EXT_STREAM);
            Document document = XMLUtils.parseDocument(stream.getContent(), true);
            List<Element> elms = XMLUtils.getElementsRecursive(document.getDocumentElement(), (element) -> {
                String elmNamespace = element.getNamespaceURI();
                return (namespace.equals(elmNamespace));
            });
            if (!elms.isEmpty()) {
                removeRelations(document, elms);
                changeRelations(document);
            }
            Document metadata = getMetadata();
            List<Element> metadataElms = XMLUtils.getElementsRecursive(metadata.getDocumentElement(), (element) -> {
                String elmNamespace = element.getNamespaceURI();
                return (namespace.equals(elmNamespace));
            });
            if (!metadataElms.isEmpty()) {
                removeRelations(document, metadataElms);
            }

        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (TransformerException e) {
            throw new RepositoryException(e);
        }
    }

    @Override
    public void removeRelationsByNameAndNamespace(String relation, String namespace) throws RepositoryException {
        try {
            // in case of incosistency
            RepositoryDatastream stream = this.getStream(FedoraUtils.RELS_EXT_STREAM);
            Document document = XMLUtils.parseDocument(stream.getContent(), true);
            List<Element> elms = XMLUtils.getElementsRecursive(document.getDocumentElement(), (element) -> {
                String elmNamespace = element.getNamespaceURI();
                String elmName = element.getLocalName();
                return (elmName.equals(relation) && namespace.equals(elmNamespace));
            });
            if (!elms.isEmpty()) {
                removeRelations(document, elms);
                changeRelations(document);
            }
            Document metadata = getMetadata();
            List<Element> metadataElms = XMLUtils.getElementsRecursive(metadata.getDocumentElement(), (element) -> {
                String elmNamespace = element.getNamespaceURI();
                String elmName = element.getLocalName();
                return (elmName.equals(relation) && namespace.equals(elmNamespace));
            });
            if (!metadataElms.isEmpty()) {
                removeRelations(document, metadataElms);
            }
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (TransformerException e) {
            throw new RepositoryException(e);
        }
    }

    private void removeRelations(Document document, List<Element> elms) throws RepositoryException, TransformerException {
        for (Element relationElement : elms) {
            if (relationElement.hasAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource")) {
                try {
                    String pid = relationElement.getAttributeNS(FedoraNamespaces.RDF_NAMESPACE_URI, "resource");
                    if (pid.startsWith(PIDParser.INFO_FEDORA_PREFIX)) {
                        pid = pid.substring(PIDParser.INFO_FEDORA_PREFIX.length());
                    }
                    this.updateSPARQL(Fedora4Repository.DELETE_RELATION(relationElement.getLocalName(),relationElement.getNamespaceURI(),repo.getObject(pid).getFullPath()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                try {
                    this.updateSPARQL(Fedora4Repository.DELETE_LITERAL(relationElement.getLocalName(),relationElement.getNamespaceURI(),relationElement.getTextContent()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            relationElement.getParentNode().removeChild(relationElement);
        }
    }

    @Override
    public boolean relationsExists(String relation, String namespace) throws RepositoryException {
        try {
            Document metadata = XMLUtils.parseDocument(this.getStream(FedoraUtils.RELS_EXT_STREAM).getContent(), true);
            Element foundElement = XMLUtils.findElement(metadata.getDocumentElement(), (element) -> {
                String elmNamespace = element.getNamespaceURI();
                String elmName = element.getLocalName();
                return (elmName.equals(relation) && namespace.equals(elmNamespace));
            });
            return foundElement != null;
        } catch (ParserConfigurationException e) {
            throw new RepositoryException(e);
        } catch (SAXException e) {
            throw new RepositoryException(e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        }
    }

    @Override
    public void removeRelationsAndRelsExt() throws RepositoryException {
        if (this.streamExists(FedoraUtils.RELS_EXT_STREAM)) {
            this.removeRelationsByNamespace(FedoraNamespaces.KRAMERIUS_URI);
            this.removeRelationsByNameAndNamespace("isMemberOfCollection",FedoraNamespaces.RDF_NAMESPACE_URI);
            this.deleteStream(FedoraUtils.RELS_EXT_STREAM);
            /*
            try {
                this.feeder.deleteByPid(this.pid);
            } catch (IOException e) {
                throw new RepositoryException(e);
            } catch (SolrServerException e) {
                throw new RepositoryException(e);
            }*/
        }
    }
}
