package cz.incad.kramerius.rest.api.k5.client.item;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import cz.incad.kramerius.*;
import cz.incad.kramerius.audio.AudioFormat;
import cz.incad.kramerius.audio.AudioStreamForwardUtils;
import cz.incad.kramerius.audio.AudioStreamId;
import cz.incad.kramerius.audio.urlMapping.RepositoryUrlManager;
import cz.incad.kramerius.fedora.utils.CDKUtils;
import cz.incad.kramerius.resourceindex.IResourceIndex;
import cz.incad.kramerius.resourceindex.ResourceIndexException;
import cz.incad.kramerius.rest.api.exceptions.ActionNotAllowed;
import cz.incad.kramerius.rest.api.exceptions.ActionNotAllowedXML;
import cz.incad.kramerius.rest.api.exceptions.GenericApplicationException;
import cz.incad.kramerius.rest.api.k5.client.JSONDecoratorsAggregate;
import cz.incad.kramerius.rest.api.k5.client.SolrMemoization;
import cz.incad.kramerius.rest.api.k5.client.item.exceptions.PIDNotFound;
import cz.incad.kramerius.rest.api.k5.client.item.utils.ItemResourceUtils;
import cz.incad.kramerius.rest.api.k5.client.utils.JSONUtils;
import cz.incad.kramerius.rest.api.k5.client.utils.PIDSupport;
import cz.incad.kramerius.security.IsActionAllowed;
import cz.incad.kramerius.security.SecuredActions;
import cz.incad.kramerius.security.SecurityException;
import cz.incad.kramerius.security.User;
import cz.incad.kramerius.service.ReplicateException;
import cz.incad.kramerius.service.ReplicationService;
import cz.incad.kramerius.service.replication.FormatType;
import cz.incad.kramerius.utils.ApplicationURL;
import cz.incad.kramerius.utils.FedoraUtils;
import cz.incad.kramerius.utils.IOUtils;
import cz.incad.kramerius.utils.StringUtils;
import cz.incad.kramerius.utils.pid.LexerException;
import cz.incad.kramerius.utils.pid.PIDParser;
import cz.incad.kramerius.virtualcollections.Collection;
import cz.incad.kramerius.virtualcollections.CollectionException;
import cz.incad.kramerius.virtualcollections.CollectionsManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.ws.rs.core.Response.ResponseBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Item endpoint
 *
 * @author pavels
 */
@Path("/v5.0/item")
public class ItemResource {

    public static final Logger LOGGER = Logger.getLogger(ItemResource.class
            .getName());

    @Inject
    @Named("securedFedoraAccess")
    FedoraAccess fedoraAccess;

    @Inject
    SolrAccess solrAccess;

    @Inject
    IResourceIndex resourceIndex;

    @Inject
    Provider<HttpServletRequest> requestProvider;

    @Inject
    Provider<HttpServletResponse> responseProvider;

    @Inject
    JSONDecoratorsAggregate decoratorsAggregate;


    @Inject
    SolrMemoization solrMemoization;

    @Inject
    IsActionAllowed isActionAllowed;

    @Inject
    ReplicationService replicationService;

    @Inject
    Provider<User> userProvider;

    // only for audio streams
    @Inject
    RepositoryUrlManager urlManager;

    @Inject
    @Named("fedora")
    CollectionsManager collectionsManager;



    @GET
    @Path("{pid}/foxml")
    @Produces({MediaType.APPLICATION_XML + ";charset=utf-8"})
    public Response foxml(@PathParam("pid") String pid) {
        boolean access = false;
        try {
            ObjectPidsPath[] paths = this.solrAccess.getPath(pid);
            if (paths.length == 0) {
                paths = this.resourceIndex.getPath(pid);
            }
            for (ObjectPidsPath path : paths) {
                if (this.isActionAllowed.isActionAllowed(SecuredActions.READ.getFormalName(), pid, null, path)) {
                    access = true;
                    break;
                }
            }
            if (access) {
                checkPid(pid);
                byte[] bytes = replicationService.getExportedFOXML(pid, FormatType.IDENTITY);
                final ByteArrayInputStream is = new ByteArrayInputStream(bytes);
                StreamingOutput stream = new StreamingOutput() {
                    public void write(OutputStream output)
                            throws IOException, WebApplicationException {
                        try {
                            IOUtils.copyStreams(is, output);
                        } catch (Exception e) {
                            throw new WebApplicationException(e);
                        }
                    }
                };
                return Response.ok().entity(stream).build();
            } else throw new ActionNotAllowedXML("access denied");
        } catch (IOException e) {
            throw new PIDNotFound("cannot parse foxml for  " + pid);
        } catch (ReplicateException e) {
            throw new PIDNotFound("cannot parse foxml for  " + pid);
        } catch (ResourceIndexException e) {
            throw new PIDNotFound("cannot parse foxml for  " + pid);
        }
    }


    @HEAD
    @Path("{pid}/streams/{dsid}")
    public Response streamHead(@PathParam("pid") String pid,
                               @PathParam("dsid") String dsid) {
        checkPid(pid);
        try {
            checkPid(pid);
            if (!FedoraUtils.FEDORA_INTERNAL_STREAMS.contains(dsid)) {
                if (!PIDSupport.isComposedPID(pid)) {

                    String externalStreamURL = fedoraAccess.getExternalStreamURL(pid, dsid);
                    LOGGER.info(String.format("Redirecting to %s", externalStreamURL));

                    if (externalStreamURL != null && (externalStreamURL.startsWith("http") || externalStreamURL.startsWith("https"))) {
                        return Response.temporaryRedirect(new URL(externalStreamURL).toURI()).build();
                    } else {
                        // audio streams - bacause of support rage in headers
                        if (FedoraUtils.AUDIO_STREAMS.contains(dsid)) {
                            // redirect

                            String mimeTypeForStream = this.fedoraAccess
                                    .getMimeTypeForStream(pid, dsid);

                            ResponseBuilder responseBuilder = Response.ok();
                            responseBuilder = responseBuilder.type(mimeTypeForStream);

                            HttpServletRequest request = this.requestProvider.get();
                            User user = this.userProvider.get();

                            AudioStreamId audioStreamId = new AudioStreamId(pid, AudioFormat.valueOf(dsid));
                            ResponseBuilder builder = AudioStreamForwardUtils.HEAD(audioStreamId, request, responseBuilder, solrAccess, user, this.isActionAllowed, urlManager);
                            return builder.build();

                        } else {
                            String mimeTypeForStream = this.fedoraAccess
                                    .getMimeTypeForStream(pid, dsid);

                            class _StreamHeadersObserver implements StreamHeadersObserver {
                                ResponseBuilder respBuilder = null;

                                @Override
                                public void observeHeaderFields(int statusCode,
                                                                Map<String, List<String>> headerFields) {
                                    respBuilder = Response.status(statusCode);
                                    Set<String> keys = headerFields.keySet();
                                    for (String k : keys) {
                                        List<String> vals = headerFields.get(k);
                                        for (String val : vals) {
                                            respBuilder.header(k, val);
                                        }
                                    }
                                }

                                public ResponseBuilder getBuider() {
                                    return this.respBuilder;
                                }
                            }

                            _StreamHeadersObserver observer = new _StreamHeadersObserver();
                            this.fedoraAccess.observeStreamHeaders(pid, dsid, observer);

                            if (observer.getBuider() != null) {
                                return observer.getBuider().type(mimeTypeForStream).build();
                            } else {
                                return Response.ok().type(mimeTypeForStream)
                                        .build();
                            }
                        }

                    }


                } else
                    throw new PIDNotFound("cannot find stream " + dsid);
            } else {
                throw new PIDNotFound("cannot find stream " + dsid);
            }
        } catch (IOException e) {
            throw new PIDNotFound(e.getMessage());
        } catch (SecurityException e) {
            throw new ActionNotAllowed(e.getMessage());
        } catch (URISyntaxException e) {
            throw new GenericApplicationException(e.getMessage());
        }


    }


    public static final int MAX_LEVEL = 6;

    public static HttpURLConnection redirectedConnection(String surl, int level) throws IOException {
        URL url = new URL(surl);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        int responseCode = urlConnection.getResponseCode();
        if (responseCode == 301 || responseCode == 302) {
            String location = urlConnection.getHeaderField("Location");
            if (level < MAX_LEVEL) {
                urlConnection.disconnect();
                return redirectedConnection(location, level+1);
            } else return null;
        } else {
            return urlConnection;
        }
    }


    @GET
    @Path("{pid}/streams/{dsid}")
    public Response stream(@PathParam("pid") String pid,
                           @PathParam("dsid") String dsid) {
        try {
            checkPid(pid);
            if (!FedoraUtils.FEDORA_INTERNAL_STREAMS.contains(dsid)) {
                if (!PIDSupport.isComposedPID(pid)) {

                    String externalStreamURL = fedoraAccess.getExternalStreamURL(pid, dsid);
                    if (externalStreamURL != null && (externalStreamURL.startsWith("http") || externalStreamURL.startsWith("https"))) {
                        Document solrDataDocument = this.solrAccess.getSolrDataDocument(pid);
                        List<String> sources = CDKUtils.findSources(solrDataDocument.getDocumentElement());
                        if (!sources.isEmpty()) {
                            Collection collection = this.collectionsManager.getCollection(sources.get(0));
                            String url = collection.getUrl();
                            if (!url.endsWith("/")) url = url +"/";
                            LOGGER.info(String.format("Redirecting to %s", String.format("%sapi/v5.0/item/%s/streams/%s", url, pid,dsid)));
                            return Response.temporaryRedirect(new URL(String.format("%sapi/v5.0/item/%s/streams/%s", url, pid,dsid)).toURI()).build();
                        } else {
                            return Response.status(508).build();
                        }
                    } else {
                        // audio streams - bacause of support rage in headers
                        if (FedoraUtils.AUDIO_STREAMS.contains(dsid)) {
                            String mimeTypeForStream = this.fedoraAccess
                                    .getMimeTypeForStream(pid, dsid);

                            ResponseBuilder responseBuilder = Response.ok();
                            responseBuilder = responseBuilder.type(mimeTypeForStream);

                            HttpServletRequest request = this.requestProvider.get();
                            User user = this.userProvider.get();
                            AudioStreamId audioStreamId = new AudioStreamId(pid, AudioFormat.valueOf(dsid));
                            ResponseBuilder builder = AudioStreamForwardUtils.GET(audioStreamId, request, responseBuilder, solrAccess, user, this.isActionAllowed, urlManager);
                            return builder.build();

                        } else {
                            final InputStream is = this.fedoraAccess.getDataStream(pid,
                                    dsid);
                            String mimeTypeForStream = this.fedoraAccess
                                    .getMimeTypeForStream(pid, dsid);
                            StreamingOutput stream = new StreamingOutput() {
                                public void write(OutputStream output)
                                        throws IOException, WebApplicationException {
                                    try {
                                        IOUtils.copyStreams(is, output);
                                    } catch (Exception e) {
                                        throw new WebApplicationException(e);
                                    }
                                }
                            };
                            return Response.ok().entity(stream).type(mimeTypeForStream)
                                    .build();
                        }
                    }
                } else
                    throw new PIDNotFound("cannot find stream " + dsid);
            } else {
                throw new PIDNotFound("cannot find stream " + dsid);
            }
        } catch (IOException e) {
            throw new PIDNotFound(e.getMessage());
        } catch (SecurityException e) {
            throw new ActionNotAllowed(e.getMessage());
        } catch (URISyntaxException e) {
            throw new GenericApplicationException(e.getMessage());
        } catch (CollectionException e) {
            throw new GenericApplicationException(e.getMessage());

        }
    }

    @GET
    @Path("{pid}/streams")
    @Produces({MediaType.APPLICATION_JSON + ";charset=utf-8"})
    public Response streams(@PathParam("pid") String pid) {
        try {
            checkPid(pid);
            JSONObject jsonObject = new JSONObject();
            if (!PIDSupport.isComposedPID(pid)) {
                List<Map<String, String>> streamsOfObject = this.fedoraAccess.getStreamsOfObject(pid);

                for (Map<String, String> stream : streamsOfObject) {
                    String dsiId = stream.get("dsid");
                    String label = stream.get("label");
                    String mimeType = stream.get("mimeType");
                    JSONObject streamObj = new JSONObject();

                    if (FedoraUtils.FEDORA_INTERNAL_STREAMS.contains(dsiId))
                        continue;
                    streamObj.put("label", label);
                    streamObj.put("mimeType", mimeType);
                    jsonObject.put(dsiId, streamObj);


                }
            }
            return Response.ok().entity(jsonObject.toString()).build();
        } catch (JSONException e1) {
            throw new GenericApplicationException(e1.getMessage());
        } catch (IOException e1) {
            throw new GenericApplicationException(e1.getMessage());
        }
    }

    @GET
    @Path("{pid}/children")
    @Produces({MediaType.APPLICATION_JSON + ";charset=utf-8"})
    public Response children(@PathParam("pid") String pid) {
        try {
            checkPid(pid);
            if (!PIDSupport.isComposedPID(pid)) {
                JSONArray jsonArray = ItemResourceUtils.decoratedJSONChildren(pid, this.solrAccess, this.solrMemoization, this.decoratorsAggregate);
                return Response.ok().entity(jsonArray.toString()).build();
            } else {
                return Response.ok().entity(new JSONArray().toString()).build();
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
            return Response.ok().entity("{}").build();
        } catch (JSONException e) {
            throw new GenericApplicationException(e.getMessage());
        }
    }

    @GET
    @Path("{pid}/siblings")
    @Produces({MediaType.APPLICATION_JSON + ";charset=utf-8"})
    public Response siblings(@PathParam("pid") String pid) {
        try {
            checkPid(pid);
            ObjectPidsPath[] paths = null;
            if (PIDSupport.isComposedPID(pid)) {
                paths = this.solrAccess.getPath(PIDSupport
                        .convertToSOLRType(pid));
            } else {
                paths = this.solrAccess.getPath(pid);
            }

            JSONArray sibsList = new JSONArray();
            for (ObjectPidsPath onePath : paths) {
                // metadata decorator
                sibsList.put(siblings(pid, onePath));
            }
            return Response.ok().entity(sibsList.toString()).build();

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new GenericApplicationException(e.getMessage());
        } catch (ProcessSubtreeException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new GenericApplicationException(e.getMessage());
        } catch (JSONException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new GenericApplicationException(e.getMessage());
        }
    }


    @GET
    @Path("{pid}/parents")
    @Produces({MediaType.APPLICATION_JSON + ";charset=utf-8"})
    public Response parents(@PathParam("pid") String pid) {
        try {
            checkPid(pid);
            List<String> parentPids = resourceIndex.getParentsPids(pid);
            JSONArray parents = new JSONArray();
            for (String parentPid : parentPids) {
                JSONObject parent = new JSONObject();
                parent.put("pid", parentPid);
                parents.put(parent);
            }
            return Response.ok().entity(parents.toString()).build();
        } catch (ResourceIndexException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new GenericApplicationException(e.getMessage());
        } catch (JSONException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new GenericApplicationException(e.getMessage());
        }
    }


    private JSONObject siblings(String pid, ObjectPidsPath onePath)
            throws ProcessSubtreeException, IOException, JSONException {

        String parentPid = null;
        List<String> children = new ArrayList<String>();
        if (onePath.getLength() >= 2) {
            String[] pth = onePath.getPathFromRootToLeaf();
            parentPid = pth[pth.length - 2];

            children = ItemResourceUtils.solrChildrenPids(parentPid, new ArrayList<String>(), this.solrAccess, this.solrMemoization);
        } else {
            children.add(pid);
        }

        JSONObject object = new JSONObject();
        JSONArray pathArray = new JSONArray();
        for (String p : onePath.getPathFromRootToLeaf()) {
            String uriString = UriBuilder.fromResource(ItemResource.class)
                    .path("{pid}/siblings").build(pid).toString();
            p = PIDSupport.convertToK4Type(p);
            JSONObject jsonObject = JSONUtils.pidAndModelDesc(p,
                    uriString, this.solrMemoization, this.decoratorsAggregate, uriString);
            pathArray.put(jsonObject);
        }
        object.put("path", pathArray);
        JSONArray jsonArray = new JSONArray();
        for (String p : children) {
            if (parentPid != null && p.equals(parentPid))
                continue;
            String uriString = UriBuilder.fromResource(ItemResource.class)
                    .path("{pid}/siblings").build(pid).toString();
            p = PIDSupport.convertToK4Type(p);
            JSONObject jsonObject = JSONUtils.pidAndModelDesc(p, uriString, this.solrMemoization, this.decoratorsAggregate, uriString);

            jsonObject.put("selected", p.equals(pid));
            jsonArray.put(jsonObject);
        }
        object.put("siblings", jsonArray);
        return object;
    }

    @GET
    @Path("{pid}/full")
    public Response full(@PathParam("pid") String pid, @QueryParam("asFile") String asFile) {
        try {
            checkPid(pid);
            if (PIDSupport.isComposedPID(pid)) {
                String fpid = PIDSupport.first(pid);
                String page = PIDSupport.rest(pid);
                int rpage = Integer.parseInt(page) - 1;
                if (rpage < 0)
                    rpage = 0;
                String suri = ApplicationURL
                        .applicationURL(this.requestProvider.get())
                        + "/img?pid="
                        + fpid
                        + "&stream=IMG_FULL&action=TRANSCODE&page=" + rpage;

                if (StringUtils.isAnyString(asFile)) {
                    suri = suri + "&asFile=true";
                }
                URI uri = new URI(suri);

                return Response.temporaryRedirect(uri).build();
            } else {

                String suri = ApplicationURL
                        .applicationURL(this.requestProvider.get())
                        + "/img?pid=" + pid + "&stream=IMG_FULL&action=GETRAW";

                if (StringUtils.isAnyString(asFile)) {
                    suri = suri + "&asFile=true";
                }

                URI uri = new URI(suri);

                return Response.temporaryRedirect(uri).build();
            }
        } catch (URISyntaxException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new PIDNotFound("pid not found '" + pid + "'");
        }
    }

    @GET
    @Path("{pid}/preview")
    public Response preview(@PathParam("pid") String pid) {
        try {
            checkPid(pid);
            if (PIDSupport.isComposedPID(pid)) {
                String fpid = PIDSupport.first(pid);
                String page = PIDSupport.rest(pid);
                int rpage = Integer.parseInt(page) - 1;
                if (rpage < 0)
                    rpage = 0;

                String suri = ApplicationURL
                        .applicationURL(this.requestProvider.get())
                        + "/img?pid="
                        + fpid
                        + "&stream=IMG_PREVIEW&action=TRANSCODE&page=" + rpage;
                URI uri = new URI(suri);
                return Response.temporaryRedirect(uri).build();
            } else {
                String suri = ApplicationURL
                        .applicationURL(this.requestProvider.get())
                        + "/img?pid="
                        + pid
                        + "&stream=IMG_PREVIEW&action=GETRAW";
                URI uri = new URI(suri);
                return Response.temporaryRedirect(uri).build();
            }
        } catch (URISyntaxException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new PIDNotFound("pid not found '" + pid + "'");
        }
    }

    @GET
    @Path("{pid}/thumb")
    public Response thumb(@PathParam("pid") String pid) {
        try {
            checkPid(pid);
            if (PIDSupport.isComposedPID(pid)) {
                String fpid = PIDSupport.first(pid);
                String page = PIDSupport.rest(pid);
                int rpage = Integer.parseInt(page) - 1;
                if (rpage < 0)
                    rpage = 0;

                String suri = ApplicationURL
                        .applicationURL(this.requestProvider.get())
                        + "/img?pid="
                        + fpid
                        + "&stream=IMG_THUMB&action=TRANSCODE&page=" + rpage;
                URI uri = new URI(suri);
                return Response.temporaryRedirect(uri).build();
            } else {
                String suri = ApplicationURL
                        .applicationURL(this.requestProvider.get())
                        + "/img?pid=" + pid + "&stream=IMG_THUMB&action=GETRAW";
                URI uri = new URI(suri);
                return Response.temporaryRedirect(uri).build();
            }
        } catch (URISyntaxException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new PIDNotFound("pid not found '" + pid + "'");
        }
    }

    private void checkPid(String pid) throws PIDNotFound {
        try {
            if (PIDSupport.isComposedPID(pid)) {
                String p = PIDSupport.first(pid);
                if (!this.fedoraAccess.isObjectAvailable(p)) {
                    throw new PIDNotFound("pid not found");
                }
            } else {
                if (!this.fedoraAccess.isObjectAvailable(pid)) {
                    throw new PIDNotFound("pid not found");
                }
            }
        } catch (IOException e) {
            throw new PIDNotFound("pid not found");
        } catch (Exception e) {
            throw new PIDNotFound("error while parsing pid (" + pid + ")");
        }
    }

    @GET
    @Path("{pid}")
    @Produces({MediaType.APPLICATION_JSON + ";charset=utf-8"})
    public Response basic(@PathParam("pid") String pid) {
        try {
            if (pid != null) {
                checkPid(pid);
                if (PIDSupport.isComposedPID(pid)) {

                    JSONObject jsonObject = new JSONObject();
                    String uriString = basicURL(pid);
                    JSONUtils.pidAndModelDesc(pid, jsonObject, uriString, this.solrMemoization,
                            this.decoratorsAggregate, null);

                    return Response.ok().entity(jsonObject.toString()).build();
                } else {
                    try {
                        PIDParser pidParser = new PIDParser(pid);
                        pidParser.objectPid();

                        JSONObject jsonObject = new JSONObject();

                        String uriString = basicURL(pid);
                        JSONUtils.pidAndModelDesc(pid, jsonObject,
                                uriString, this.solrMemoization,
                                this.decoratorsAggregate, null);

                        return Response.ok().entity(jsonObject.toString())
                                .build();
                    } catch (IllegalArgumentException e) {
                        throw new GenericApplicationException(e.getMessage());
                    } catch (UriBuilderException e) {
                        throw new GenericApplicationException(e.getMessage());
                    } catch (LexerException e) {
                        throw new GenericApplicationException(e.getMessage());
                    }
                }
            } else {
                throw new PIDNotFound("pid not found '" + pid + "'");
            }
        } catch (IOException e) {
            throw new PIDNotFound("pid not found '" + pid + "'");
        } catch (JSONException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new GenericApplicationException(e.getMessage());
        }
    }

    /**
     * Basic URL
     *
     * @param pid
     * @return
     */
    public static String basicURL(String pid) {
        String uriString = UriBuilder.fromResource(ItemResource.class)
                .path("{pid}").build(pid).toString();
        return uriString;
    }

}
