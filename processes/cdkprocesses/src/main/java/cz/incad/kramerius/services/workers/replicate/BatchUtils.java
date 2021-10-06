package cz.incad.kramerius.services.workers.replicate;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import cz.incad.kramerius.utils.UTFSort;
import org.apache.commons.lang3.StringEscapeUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import cz.incad.kramerius.service.MigrateSolrIndexException;
import cz.incad.kramerius.utils.XMLUtils;
import cz.incad.kramerius.utils.conf.KConfiguration;

public class BatchUtils {


    private BatchUtils() {}

    public static final Logger LOGGER = Logger.getLogger(BatchUtils.class.getName());


    public static Document batch(Element resultElem, boolean compositeId, String root, String child) throws ParserConfigurationException, MigrateSolrIndexException  {
        //List<String> removalSourceElements = itemsToRemove();
        Document destBatch = XMLUtils.crateDocument("add");
        List<Element> docs = XMLUtils.getElements(resultElem, new XMLUtils.ElementsFilter() {
            @Override
            public boolean acceptElement(Element elm) {
                return  (elm.getNodeName().equals("doc"));
            }
        });

        for (int i = 0; i < docs.size(); i++) {
            Element destDocElement = destBatch.createElement("doc");
            destBatch.getDocumentElement().appendChild(destDocElement);
            Element sourceDocElm = docs.get(i);
            k5transform(sourceDocElm, destBatch, destDocElement, compositeId, root, child);
        }
        return destBatch;
    }




    /** find element by attribute */
    public static Element findByAttribute(Element sourceDocElm, String attName) {
        Element elemName = XMLUtils.findElement(sourceDocElm,  new XMLUtils.ElementsFilter() {
            @Override
            public boolean acceptElement(Element element) {
                return element.getAttribute("name").equals(attName);
            }
        });
        return elemName;
    }

    /** find pid in source doc */
    public static String pid(Element sourceDocElm) {
        Element pidElm = XMLUtils.findElement(sourceDocElm,  new XMLUtils.ElementsFilter() {
            @Override
            public boolean acceptElement(Element element) {
                if (element.getNodeName().equals("str")) {
                    return element.getAttribute("name").equals("PID");
                }
                return false;
            }
        });
        if (pidElm != null) {
            return pidElm.getTextContent().trim();
        } else return "";
    }

    /** transforming fields in k5 index; it doesn't apply if an index is K7 */
    public static void k5transform(Element sourceDocElm, Document destDocument, Element destDocElem, boolean compositeId, String root, String child) throws MigrateSolrIndexException  {
        String pid = pid(sourceDocElm);
        if (sourceDocElm.getNodeName().equals("doc")) {
            NodeList childNodes = sourceDocElm.getChildNodes();
            for (int j = 0,lj=childNodes.getLength(); j < lj; j++) {
                Node node = childNodes.item(j);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    List<String> primitiveVals = Arrays.asList("str","int","bool", "date");
                    if (primitiveVals.contains(node.getNodeName())) {
                        simpleValue(pid, destDocument,destDocElem, node,null, false);
                    } else {
                        arrayValue(pid,sourceDocElm, destDocument,destDocElem,node);
                    }
                }
            }
            browseAuthorsAndTitles(sourceDocElm, destDocument, destDocElem);
            // composite id is not supported
            if (compositeId && root != null && child != null) {
                enhanceByCompositeId(destDocument, destDocElem, root, child);
            }
        }
    }
    
    public static void enhanceByCompositeId(Document ndoc,Element docElm, String root, String child) {
        Element pidElm = XMLUtils.findElement(docElm, new XMLUtils.ElementsFilter() {
            
            @Override
            public boolean acceptElement(Element paramElement) {
                String attribute = paramElement.getAttribute("name");
                return attribute.equals(child);
            }
        });
        Element rootPidElm = XMLUtils.findElement(docElm, new XMLUtils.ElementsFilter() {
            
            @Override
            public boolean acceptElement(Element paramElement) {
                String attribute = paramElement.getAttribute("name");
                return attribute.equals(root);
            }
        });
        
            
        
        String txt = rootPidElm.getTextContent().trim()+"!"+pidElm.getTextContent().trim();
        Element compositeIdElm = ndoc.createElement("field");
        String compositeIdName = System.getProperty("compositeId.field.name","compositeId");
        compositeIdElm.setAttribute("name", compositeIdName);
        compositeIdElm.setTextContent(txt);
        docElm.appendChild(compositeIdElm);
    }
    
    public static void simpleValue(String pid, Document feedDoc, Element feedDocElm, Node node, String derivedName, boolean dontCareAboutNonCopiingFields) {
        String attributeName = derivedName != null ? derivedName : ((Element)node).getAttribute("name");
        if (dontCareAboutNonCopiingFields || !nonCopiingField(attributeName)) {
            Element strElm = feedDoc.createElement("field");
            strElm.setAttribute("name", attributeName);
            feedDocElm.appendChild(strElm);
            String content = StringEscapeUtils.escapeXml(node.getTextContent());
            strElm.setTextContent(content);
        }
    }

    public static void arrayValue(String pid,Element sourceDocElement, Document feedDoc, Element feedDocElement, Node node) {
        String attributeName = ((Element) node).getAttribute("name");
        if (!nonCopiingField(attributeName)) {
            if (exceptionField(attributeName) && pid.contains("/@")) {
                NodeList childNodes = node.getChildNodes();
                for (int i = 0,ll=childNodes.getLength(); i < ll; i++) {
                    Node n = childNodes.item(i);
                    if (n.getNodeType() == Node.ELEMENT_NODE) {
                        // exception again !!! uuugrrrr !!;

                        // bug in pdf; text is filled directly to text field although text is copied field
                        // first we have to find text_ocr, if it doesn't exist, copy whole text to text, text_lemmatized, text_lemmatized_ascii and text_lemmatized_nostopwords
                        Element textOcr = findByAttribute(sourceDocElement, "text_ocr");
                        if (textOcr == null) {
                            simpleValue(pid, feedDoc,feedDocElement, n, attributeName, false);
                            simpleValue(pid, feedDoc, feedDocElement, n,"text_lemmatized", true);
                            simpleValue(pid, feedDoc, feedDocElement, n,"text_lemmatized_ascii", true);
                            simpleValue(pid, feedDoc, feedDocElement, n,"text_lemmatized_nostopwords", true);
                        }
                    }
                }
            } else if (!exceptionField(attributeName)) {
                NodeList childNodes = node.getChildNodes();
                for (int i = 0,ll=childNodes.getLength(); i < ll; i++) {
                    Node n = childNodes.item(i);
                    if (n.getNodeType() == Node.ELEMENT_NODE) {
                        simpleValue(pid, feedDoc,feedDocElement, n, attributeName, false);
                    }
                }
            }
        }
    }

    // text is copied but not for PDF; uuugrrrr !!! Terrible
    public static final List<String> EXCEPTION_FIELDS = Arrays.asList("text");

    // copied
    public static final List<String> COPIED_FIELDS = Arrays.asList("title", "search_title","facet_autor","search_autor");
    // copied but identified by postfix
    public static final List<String> COPIED_POSTFIXES = Arrays.asList("_lemmatized","_lemmatized_ascii","_lemmatized_nostopwords");

    private static boolean exceptionField(String attributeName) {
        if (EXCEPTION_FIELDS.contains(attributeName)) {
            return true;
        }
        return false;
    }

    private static boolean nonCopiingField(String attributeName) {
        if (COPIED_FIELDS.contains(attributeName)) {
            return true;
        }
        for (String postfix:
             COPIED_POSTFIXES) {
            if (attributeName.endsWith(postfix)) return true;
        }
        return false;
    }


    // special not stored fields  browse_autor, browse_title
    public static void browseAuthorsAndTitles(Element sourceDocElm,Document ndoc, Element docElm)  {
        try {
            UTFSort utf_sort = new UTFSort();
            //FedoraOperations operations = new FedoraOperations();

            // browse author -- skip
            Element browseAuthorInSource = XMLUtils.findElement(sourceDocElm, new XMLUtils.ElementsFilter() {
                @Override
                public boolean acceptElement(Element element) {
                    return element.getAttribute("name").equals("browse_autor");
                }
            });
            // browse title doens't exist
            if (browseAuthorInSource == null) {
                Element dcCreators = XMLUtils.findElement(sourceDocElm, new XMLUtils.ElementsFilter() {

                    @Override
                    public boolean acceptElement(Element e) {
                        String attribute = e.getAttribute("name");
                        return attribute.equals("dc.creator");
                    }
                });
                if (dcCreators != null) {
                    List<Element> dcCreatorsStrings = XMLUtils.getElements(dcCreators);
                    for (Element author : dcCreatorsStrings) {
                        //<xsl:value-of select="exts:prepareCzech($generic, text())"/>##<xsl:value-of select="text()"/>
                        String textContent = author.getTextContent();
                        String prepared =  utf_sort.translate(textContent)+"##"+textContent;
                        //String prepared = operations.prepareCzech(textContent)+"##"+textContent;
                        Element strElm = ndoc.createElement("field");
                        strElm.setAttribute("name", "browse_autor");
                        docElm.appendChild(strElm);
                        strElm.setTextContent(prepared);
                    }
                }

            }


            // browse author -- skip
            Element browseTitleInSource = XMLUtils.findElement(sourceDocElm, new XMLUtils.ElementsFilter() {
                @Override
                public boolean acceptElement(Element element) {
                    return element.getAttribute("name").equals("browse_title");
               }
            });

            if (browseTitleInSource == null) {
                Element model = XMLUtils.findElement(sourceDocElm, new XMLUtils.ElementsFilter() {

                    @Override
                    public boolean acceptElement(Element e) {
                        String attribute = e.getAttribute("name");
                        return attribute.equals("fedora.model");
                    }
                });


                Element dcTitle = XMLUtils.findElement(sourceDocElm, new XMLUtils.ElementsFilter() {

                    @Override
                    public boolean acceptElement(Element e) {
                        String attribute = e.getAttribute("name");
                        return attribute.equals("dc.title");
                    }
                });

                if (dcTitle != null && model != null &&  Arrays.asList(KConfiguration.getInstance().getConfiguration().getStringArray("indexer.browseModels")).contains(model.getTextContent().trim())) {
                    Element strElm = ndoc.createElement("field");
                    strElm.setAttribute("name", "browse_title");
                    docElm.appendChild(strElm);
                    String textContent = dcTitle.getTextContent();
                    String prepared = utf_sort.translate(textContent)+"##"+textContent;
                    strElm.setTextContent(prepared);
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,e.getMessage(), e);
            throw new RuntimeException(e.getMessage());
        }
    }
}
