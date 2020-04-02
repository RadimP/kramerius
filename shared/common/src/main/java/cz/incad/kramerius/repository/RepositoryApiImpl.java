package cz.incad.kramerius.repository;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.qbizm.kramerius.imp.jaxb.DigitalObject;
import cz.incad.kramerius.fedora.om.RepositoryDatastream;
import cz.incad.kramerius.fedora.om.RepositoryException;
import cz.incad.kramerius.fedora.om.RepositoryObject;
import cz.incad.kramerius.fedora.om.impl.AkubraDOManager;
import cz.incad.kramerius.fedora.om.impl.AkubraRepository;
import cz.incad.kramerius.repository.utils.Utils;
import cz.incad.kramerius.resourceindex.ProcessingIndexFeeder;
import cz.incad.kramerius.utils.Dom4jUtils;
import cz.incad.kramerius.utils.conf.KConfiguration;
import org.apache.solr.client.solrj.SolrServerException;
import org.dom4j.*;
import org.ehcache.CacheManager;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class RepositoryApiImpl implements RepositoryApi {

    private static final Namespace NS_FOXML = new Namespace("foxml", "info:fedora/fedora-system:def/foxml#");
    private final AkubraRepository akubraRepository;
    private final Unmarshaller digitalObjectUnmarshaller;

    @Inject
    public RepositoryApiImpl(KConfiguration configuration, ProcessingIndexFeeder processingIndexFeeder, @Named("akubraCacheManager") CacheManager cacheManager) throws RepositoryException {
        try {
            AkubraDOManager akubraDOManager = new AkubraDOManager(configuration, cacheManager);
            this.akubraRepository = (AkubraRepository) AkubraRepository.build(processingIndexFeeder, akubraDOManager);
            this.digitalObjectUnmarshaller = JAXBContext.newInstance(DigitalObject.class).createUnmarshaller();
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (JAXBException e) {
            throw new RepositoryException("Error initializing JAXB unmarshaller for " + DigitalObject.class.getName());
        }
    }

    @Override
    public void ingestObject(Document foxmlDoc) throws RepositoryException, IOException {
        DigitalObject digitalObject = foxmlDocToDigitalObject(foxmlDoc);
        akubraRepository.ingestObject(digitalObject);
        //TODO: make sure, that indexed in processing index
    }

    @Override
    public boolean objectExists(String pid) throws RepositoryException {
        return akubraRepository.objectExists(pid);
    }

    @Override
    public String getProperty(String pid, String propertyName) throws IOException, RepositoryException {
        Document objectFoxml = getFoxml(pid);
        return objectFoxml == null ? null : extractProperty(objectFoxml, propertyName);
    }

    @Override
    public String getPropertyLabel(String pid) throws IOException, RepositoryException {
        return getProperty(pid, "info:fedora/fedora-system:def/model#label");
    }

    @Override
    public LocalDateTime getPropertyCreated(String pid) throws IOException, RepositoryException {
        String propertyValue = getProperty(pid, "info:fedora/fedora-system:def/model#createdDate");
        if (propertyValue != null) {
            try {
                return LocalDateTime.parse(propertyValue, RepositoryApi.TIMESTAMP_FORMATTER);
            } catch (DateTimeParseException e) {
                System.out.println(String.format("cannot parse createdeDate %s from object %s", propertyValue, pid));
            }
        }
        return null;
    }

    @Override
    public LocalDateTime getPropertyLastModified(String pid) throws IOException, RepositoryException {
        String propertyValue = getProperty(pid, "info:fedora/fedora-system:def/view#lastModifiedDate");
        if (propertyValue != null) {
            try {
                return LocalDateTime.parse(propertyValue, RepositoryApi.TIMESTAMP_FORMATTER);
            } catch (DateTimeParseException e) {
                System.out.println(String.format("cannot parse lastModifiedDate %s from object %s", propertyValue, pid));
            }
        }
        return null;
    }

    @Override
    public Document getFoxml(String pid) throws RepositoryException, IOException {
        RepositoryObject object = akubraRepository.getObject(pid);
        return Utils.inputstreamToDocument(object.getFoxml(), true);
    }

    @Override
    public boolean datastreamExists(String pid, String dsId) throws RepositoryException, IOException {
        RepositoryObject object = akubraRepository.getObject(pid);
        return object == null ? false : object.streamExists(dsId);
    }

    @Override
    public Document getLatestVersionOfInlineXmlDatastream(String pid, String dsId) throws RepositoryException, IOException {
        RepositoryObject object = akubraRepository.getObject(pid);
        if (object.streamExists(dsId)) {
            RepositoryDatastream stream = object.getStream(dsId);
            return Utils.inputstreamToDocument(stream.getContent(), true);
        } else {
            return null;
        }
    }

    @Override
    public List<String> getObjectPidsByModel(String model) throws RepositoryException, IOException, SolrServerException {
        List<String> pids = new ArrayList<>();
        //TODO: offset, limit
        //TODO sort by date desc
        String query = String.format("type:description AND model:%s", "model\\:" + model); //prvni "model:" je filtr na solr pole, druhy "model:" je hodnota pole, coze  uprime zbytecne
        akubraRepository.getProcessingIndexFeeder().iterateProcessing(query, (doc) -> {
            Object fieldValue = doc.getFieldValue("source");
            if (fieldValue != null) {
                String valueStr = fieldValue.toString();
                pids.add(valueStr);
            }
        });
        return pids;
    }

    @Override
    public List<String> getTripletTargets(String source, String relation) throws RepositoryException, IOException, SolrServerException {
        List<String> pids = new ArrayList<>();
        String query = String.format("source:%s AND relation:%s", source, relation);
        akubraRepository.getProcessingIndexFeeder().iterateProcessing(query, (doc) -> {
            Object fieldValue = doc.getFieldValue("targetPid");
            if (fieldValue != null) {
                String valueStr = fieldValue.toString();
                pids.add(valueStr);
            }
        });
        return pids;
    }

    @Override
    public List<String> getTripletSources(String relation, String target) throws RepositoryException, IOException, SolrServerException {
        List<String> pids = new ArrayList<>();
        String query = String.format("relation:%s AND targetPid:%s", relation, target);
        akubraRepository.getProcessingIndexFeeder().iterateProcessing(query, (doc) -> {
            Object fieldValue = doc.getFieldValue("source");
            if (fieldValue != null) {
                String valueStr = fieldValue.toString();
                pids.add(valueStr);
            }
        });
        return pids;
    }

    @Override
    public void updateInlineXmlDatastream(String pid, String dsId, Document streamDoc, String formatUri) throws RepositoryException, IOException {
        Document foxml = getFoxml(pid);
        appendNewInlineXmlDatastreamVersion(foxml, dsId, streamDoc, formatUri);
        updateLastModifiedTimestamp(foxml);
        DigitalObject updatedDigitalObject = foxmlDocToDigitalObject(foxml);
        akubraRepository.deleteobject(pid);
        akubraRepository.ingestObject(updatedDigitalObject);
        //TODO: make sure, that re-indexed in processing index
    }

    private void updateLastModifiedTimestamp(Document foxml) {
        Attribute valueAttr = (Attribute) Dom4jUtils.buildXpath("/foxml:digitalObject/foxml:objectProperties/foxml:property[@NAME='info:fedora/fedora-system:def/view#lastModifiedDate']/@VALUE").selectSingleNode(foxml);
        if (valueAttr != null) {
            valueAttr.setValue(LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        } else {
            Element objectProperties = (Element) Dom4jUtils.buildXpath("/foxml:digitalObject/foxml:objectProperties").selectSingleNode(foxml);
            Element propertyLastModified = objectProperties.addElement(new QName("property", NS_FOXML));
            propertyLastModified.addAttribute("NAME", "info:fedora/fedora-system:def/view#lastModifiedDate");
            propertyLastModified.addAttribute("VALUE", LocalDateTime.now().format(RepositoryApi.TIMESTAMP_FORMATTER));
        }
    }

    private void appendNewInlineXmlDatastreamVersion(Document foxml, String dsId, Document streamDoc, String formatUri) {
        Element datastreamEl = (Element) Dom4jUtils.buildXpath(String.format("/foxml:digitalObject/foxml:datastream[@ID='%s']", dsId)).selectSingleNode(foxml);
        if (datastreamEl != null) {
            int latestDsIdVersion = extractLatestDsIdVersion(datastreamEl);
            int newDsIdVesion = latestDsIdVersion + 1;
            Element dsVersionEl = datastreamEl.addElement("datastreamVersion", NAMESPACE_FOXML);
            dsVersionEl.addAttribute("ID", dsId + "." + newDsIdVesion);
            dsVersionEl.addAttribute("CREATED", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
            dsVersionEl.addAttribute("MIMETYPE", "application/xml");
            if (formatUri != null) {
                dsVersionEl.addAttribute("FORMAT_URI", formatUri);
            }
            Element xmlContentEl = dsVersionEl.addElement("xmlContent", NAMESPACE_FOXML);
            xmlContentEl.add(streamDoc.getRootElement().detach());
        }
    }

    private int extractLatestDsIdVersion(Element datastreamEl) {
        List<Node> dsVersionEls = Dom4jUtils.buildXpath("foxml:datastreamVersion").selectNodes(datastreamEl);
        int maxVersion = -1;
        for (Node node : dsVersionEls) {
            Element versionEl = (Element) node;
            String ID = Dom4jUtils.stringOrNullFromAttributeByName(versionEl, "ID");
            int versionNumber = Integer.valueOf(ID.split("\\.")[1]);
            if (versionNumber > maxVersion) {
                maxVersion = versionNumber;
            }
        }
        return maxVersion;
    }

    @Override
    public void deleteObject(String pid) throws RepositoryException, IOException {
        akubraRepository.deleteobject(pid);
    }

    private DigitalObject foxmlDocToDigitalObject(Document foxml) throws IOException {
        try {
            return (DigitalObject) digitalObjectUnmarshaller.unmarshal(new StringReader(foxml.asXML()));
        } catch (JAXBException e) {
            throw new IOException(e);
        }
    }

    private String extractProperty(Document foxmlDoc, String name) {
        Node node = Dom4jUtils.buildXpath(String.format("/foxml:digitalObject/foxml:objectProperties/foxml:property[@NAME='%s']/@VALUE", name)).selectSingleNode(foxmlDoc);
        return node == null ? null : Dom4jUtils.toStringOrNull(node);
    }
}