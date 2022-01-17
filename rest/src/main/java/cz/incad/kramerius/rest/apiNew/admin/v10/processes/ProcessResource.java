package cz.incad.kramerius.rest.apiNew.admin.v10.processes;

import cz.incad.kramerius.ObjectPidsPath;
import cz.incad.kramerius.processes.*;
import cz.incad.kramerius.processes.mock.ProcessApiTestProcess;
import cz.incad.kramerius.processes.new_api.*;
import cz.incad.kramerius.rest.api.processes.LRResource;
import cz.incad.kramerius.rest.apiNew.admin.v10.*;
import cz.incad.kramerius.rest.apiNew.exceptions.*;
import cz.incad.kramerius.security.RightsResolver;
import cz.incad.kramerius.security.SecuredActions;
import cz.incad.kramerius.security.SpecialObjects;
import cz.incad.kramerius.security.User;
import cz.incad.kramerius.users.LoggedUsersSingleton;
import cz.incad.kramerius.utils.StringUtils;
import cz.kramerius.searchIndex.indexerProcess.IndexationType;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/admin/v1.0/processes")
public class ProcessResource extends AdminApiResource {

    public static Logger LOGGER = Logger.getLogger(ProcessResource.class.getName());

    private static final Integer GET_BATCHES_DEFAULT_OFFSET = 0;
    private static final Integer GET_BATCHES_DEFAULT_LIMIT = 10;

    private static final Integer GET_LOGS_DEFAULT_OFFSET = 0;
    private static final Integer GET_LOGS_DEFAULT_LIMIT = 10;


    //TODO: prejmenovat role podle spravy uctu
    private static final String ROLE_SCHEDULE_PROCESSES = "kramerius_admin";
    private static final String ROLE_READ_PROCESSES = "kramerius_admin";
    private static final String ROLE_READ_PROCESS_OWNERS = "kramerius_admin";
    private static final String ROLE_DELETE_PROCESSES = "kramerius_admin";
    private static final String ROLE_CANCEL_OR_KILL_PROCESSES = "kramerius_admin";

    @Inject
    LRProcessManager lrProcessManager; //here only for scheduling

    /*@Inject
    DefinitionManager definitionManager; //process definitions*/

    /*@Inject
    Provider<HttpServletRequest> requestProvider;*/

    @Inject
    LoggedUsersSingleton loggedUsersSingleton;

    @Inject
    RightsResolver rightsResolver;

    @Inject
    ProcessManager processManager;

    @Inject
    ProcessSchedulingHelper processSchedulingHelper;

    /**
     * Returns list of users who have scheduled some process
     *
     * @return
     */
    @GET
    @Path("owners")
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    public Response getOwners() {
        try {
            //authentication
            AuthenticatedUser user = getAuthenticatedUserByOauth();
            String role = ROLE_READ_PROCESS_OWNERS;
            if (!user.getRoles().contains(role)) {
                throw new ForbiddenException("user '%s' is not allowed to manage processes (missing role '%s')", user.getName(), role); //403
            }
            //get data from db
            List<ProcessOwner> owners = this.processManager.getProcessesOwners();
            //sort
            owners.sort((o1, o2) -> {
                if (o1.name.startsWith("_") && o1.name.startsWith("_")) {
                    return o1.name.compareTo(o2.name);
                } else if (o1.name.startsWith("_")) {
                    return 1;
                } else {
                    return -1;
                }
            });
            //convert to JSON
            JSONArray ownersJson = new JSONArray();
            for (ProcessOwner owner : owners) {
                JSONObject ownerJson = new JSONObject();
                ownerJson.put("id", owner.id);
                ownerJson.put("name", owner.name);
                ownersJson.put(ownerJson);
            }
            JSONObject result = new JSONObject();
            result.put("owners", ownersJson);
            //return
            return Response.ok().entity(result.toString()).build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    @GET
    @Path("by_process_id/{process_id}")
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    public Response getProcessByProcessId(@PathParam("process_id") String processId) {
        try {
            //authentication
            AuthenticatedUser user = getAuthenticatedUserByOauth();
            String role = ROLE_READ_PROCESSES;
            if (!user.getRoles().contains(role)) {
                throw new ForbiddenException("user '%s' is not allowed to manage processes (missing role '%s')", user.getName(), role); //403
            }
            //id
            Integer processIdInt = null;
            if (StringUtils.isAnyString(processId)) {
                try {
                    processIdInt = Integer.valueOf(processId);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("process_id must be a number, '%s' is not", processId);
                }
            }
            //get process (& it's batch) data from db
            ProcessInBatch processInBatch = processManager.getProcessInBatchByProcessId(processIdInt);
            if (processInBatch == null) {
                throw new NotFoundException("there's no process with process_id=" + processId);
            }
            JSONObject result = processInBatchToJson(processInBatch);
            return Response.ok().entity(result.toString()).build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }


    /**
     * Get whole OUT log file
     *
     * @param processUuid
     */
    @GET
    @Path("by_process_uuid/{process_uuid}/logs/out")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getProcessLogsOutByProcessUuid(@PathParam("process_uuid") String processUuid,
                                                   @DefaultValue("out.txt") @QueryParam("fileName") String fileName) {
        try {
            return getProcessLogsFileByProcessUuid(processUuid, ProcessLogsHelper.LogType.OUT, fileName);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    /**
     * Get whole ERR log file
     *
     * @param processUuid
     */
    @GET
    @Path("by_process_uuid/{process_uuid}/logs/err")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)

    public Response getProcessLogsErrByProcessUuid(@PathParam("process_uuid") String processUuid,
                                                   @DefaultValue("err.txt") @QueryParam("fileName") String fileName) {
        try {
            return getProcessLogsFileByProcessUuid(processUuid, ProcessLogsHelper.LogType.ERR, fileName);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    private Response getProcessLogsFileByProcessUuid(String processUuid, ProcessLogsHelper.LogType logType, String fileName) {
        //resource is secured by difficulty of guessing uuid, also client needs this to be accessible without authentication
        //access to process data
        LRProcess lrProces = lrProcessManager.getLongRunningProcess(processUuid);
        if (lrProces == null) {
            throw new BadRequestException("process with uuid " + processUuid + " not found");
        }
        ProcessLogsHelper processLogsHelper = new ProcessLogsHelper(lrProces);
        InputStream processInputStream = processLogsHelper.getLogsFileWhole(logType);
        return Response.ok().entity(processInputStream)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    /**
     * Nahrazuje _processes_logs_std_json.jsp, _processes_logs_std_json.jsp
     *
     * @param processUuid
     * @param offsetStr
     * @param limitStr
     * @return JSON with selected lines (defined by offset, limit) of the standard log
     * @see cz.incad.Kramerius.views.ProcessLogsViewObject
     */
    @GET
    @Path("by_process_uuid/{process_uuid}/logs/out/lines")
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    public Response getProcessLogsOutLinesByProcessUuid(@PathParam("process_uuid") String processUuid,
                                                        @QueryParam("offset") String offsetStr,
                                                        @QueryParam("limit") String limitStr) {
        try {
            return getProcessLogsLinesByProcessUuid(processUuid, ProcessLogsHelper.LogType.OUT, offsetStr, limitStr);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    /**
     * Nahrazuje _processes_logs_err_json.jsp, _processes_logs_err_json.jsp
     *
     * @param processUuid
     * @param offsetStr
     * @param limitStr
     * @return JSON with selected lines (defined by offset, limit) of the error log
     * @see cz.incad.Kramerius.views.ProcessLogsViewObject
     */
    @GET
    @Path("by_process_uuid/{process_uuid}/logs/err/lines")
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    public Response getProcessLogsErrLinesByProcessUuid(@PathParam("process_uuid") String processUuid,
                                                        @QueryParam("offset") String offsetStr,
                                                        @QueryParam("limit") String limitStr) {
        try {
            return getProcessLogsLinesByProcessUuid(processUuid, ProcessLogsHelper.LogType.ERR, offsetStr, limitStr);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    private Response getProcessLogsLinesByProcessUuid(String processUuid, ProcessLogsHelper.LogType logType, String offsetStr, String limitStr) {
        //authentication
        AuthenticatedUser user = getAuthenticatedUserByOauth();
        String role = ROLE_READ_PROCESSES;
        if (!user.getRoles().contains(role)) {
            throw new ForbiddenException("user '%s' is not allowed to manage processes (missing role '%s')", user.getName(), role); //403
        }
        //offset & limit
        long offset = GET_LOGS_DEFAULT_OFFSET;
        if (StringUtils.isAnyString(offsetStr)) {
            try {
                offset = Long.valueOf(offsetStr);
                if (offset < 0) {
                    throw new BadRequestException("offset must be zero or a positive number, '%s' is not", offsetStr);
                }
            } catch (NumberFormatException e) {
                throw new BadRequestException("offset must be a number, '%s' is not", offsetStr);
            }
        }
        long limit = GET_LOGS_DEFAULT_LIMIT;
        if (StringUtils.isAnyString(limitStr)) {
            try {
                limit = Long.valueOf(limitStr);
                if (limit < 1) {
                    throw new BadRequestException("limit must be a positive number, '%s' is not", limitStr);
                }
            } catch (NumberFormatException e) {
                throw new BadRequestException("limit must be a number, '%s' is not", limitStr);
            }
        }
        //access to process data
        LRProcess lrProces = lrProcessManager.getLongRunningProcess(processUuid);
        if (lrProces == null) {
            throw new BadRequestException("process with uuid " + processUuid + " not found");
        }
        ProcessLogsHelper processLogsHelper = new ProcessLogsHelper(lrProces);
        //result
        JSONObject result = new JSONObject();
        result.put("total_size", processLogsHelper.getLogsFileSize(logType));
        JSONArray linesJson = new JSONArray();
        List<String> lines = processLogsHelper.getLogsFileData(logType, offset, limit);
        for (String line : lines) {
            linesJson.put(line);
        }
        result.put("lines", linesJson);
        return Response.ok().entity(result.toString()).build();
    }

    private JSONObject processInBatchToJson(ProcessInBatch processInBatch) {
        JSONObject json = new JSONObject();
        //batch
        JSONObject batchJson = new JSONObject();
        batchJson.put("token", processInBatch.batchToken);
        batchJson.put("id", processInBatch.batchId);
        batchJson.put("state", toBatchStateName(processInBatch.batchStateCode));
        batchJson.put("planned", Utils.toFormattedStringOrNull(processInBatch.batchPlanned));
        batchJson.put("started", Utils.toFormattedStringOrNull(processInBatch.batchStarted));
        batchJson.put("finished", Utils.toFormattedStringOrNull(processInBatch.batchFinished));
        batchJson.put("owner_id", processInBatch.batchOwnerId);
        batchJson.put("owner_name", processInBatch.batchOwnerName);
        json.put("batch", batchJson);
        //process
        JSONObject processJson = new JSONObject();
        processJson.put("id", processInBatch.processId);
        processJson.put("uuid", processInBatch.processUuid);
        processJson.put("defid", processInBatch.processDefid);
        processJson.put("name", processInBatch.processName);
        processJson.put("state", toProcessStateName(processInBatch.processStateCode));
        processJson.put("planned", Utils.toFormattedStringOrNull(processInBatch.processPlanned));
        processJson.put("started", Utils.toFormattedStringOrNull(processInBatch.processStarted));
        processJson.put("finished", Utils.toFormattedStringOrNull(processInBatch.processFinished));
        JSONObject result = new JSONObject();
        result.put("process", processJson);
        result.put("batch", batchJson);
        return result;
    }

    @DELETE
    @Path("batches/by_first_process_id/{process_id}")
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    public Response deleteBatch(@PathParam("process_id") String processId) {
        try {
            //authentication
            AuthenticatedUser user = getAuthenticatedUserByOauth();
            String role = ROLE_DELETE_PROCESSES;
            if (!user.getRoles().contains(role)) {
                throw new ForbiddenException("user '%s' is not allowed to manage processes (missing role '%s')", user.getName(), role); //403
            }
            //id
            Integer processIdInt = null;
            if (StringUtils.isAnyString(processId)) {
                try {
                    processIdInt = Integer.valueOf(processId);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("process_id must be a number, '%s' is not", processId);
                }
            }
            //get batch data from db
            Batch batch = this.processManager.getBatchByFirstProcessId(processIdInt);
            if (batch == null) {
                throw new BadRequestException("batch with first-process-id %d doesn't exist", processIdInt);
            }
            //check batch is deletable
            String batchState = toBatchStateName(batch.stateCode);
            if (!isDeletableState(batchState)) {
                throw new BadRequestException("batch in state %s cannot be deleted", batchState);
            }
            //delete processes in batch
            int deleted = this.processManager.deleteBatchByBatchToken(batch.token);
            //return
            JSONObject result = new JSONObject();
            result.put("batch_id", batch.firstProcessId);
            result.put("batch_token", batch.token);
            result.put("processes_deleted", deleted);
            return Response.ok().entity(result.toString()).build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    @DELETE
    @Path("batches/by_first_process_id/{process_id}/execution")
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    public Response killBatch(@PathParam("process_id") String processId) {
        try {
            //authentication
            AuthenticatedUser user = getAuthenticatedUserByOauth();
            String role = ROLE_CANCEL_OR_KILL_PROCESSES;
            if (!user.getRoles().contains(role)) {
                throw new ForbiddenException("user '%s' is not allowed to manage processes (missing role '%s')", user.getName(), role); //403
            }
            //id
            Integer processIdInt = null;
            if (StringUtils.isAnyString(processId)) {
                try {
                    processIdInt = Integer.valueOf(processId);
                } catch (NumberFormatException e) {
                    throw new BadRequestException("process_id must be a positive number, '%s' is not", processId);
                }
            }
            //get batch data from db
            Batch batch = this.processManager.getBatchByFirstProcessId(processIdInt);
            if (batch == null) {
                throw new BadRequestException("batch with first-process-id %d doesn't exist", processIdInt);
            }

            //kill all processes in batch if possible
            String batchState = toBatchStateName(batch.stateCode);
            if (isKillableState(batchState)) {
                List<ProcessInBatch> processes = processManager.getProcessesInBatchByFirstProcessId(processIdInt);
                for (ProcessInBatch process : processes) {
                    String uuid = process.processUuid;
                    LRProcess lrProcess = lrProcessManager.getLongRunningProcess(uuid);
                    if (lrProcess != null && !States.notRunningState(lrProcess.getProcessState())) {
                        //System.out.println(lrProcess.getProcessState());
                        try {
                            lrProcess.stopMe();
                            lrProcessManager.updateLongRunningProcessFinishedDate(lrProcess);
                        } catch (Throwable e) { //because AbstractLRProcessImpl.stopMe() throws java.lang.IllegalStateException: cannot stop this process! No PID associated
                            e.printStackTrace();
                        }
                    } else {
                        System.out.println(lrProcess.getProcessState());
                    }
                }
            }
            return Response.ok().build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    /**
     * Returns filtered batches
     *
     * @param offsetStr   offset
     * @param limitStr    limit
     * @param filterOwner filter to batches run by user with this id (login)
     * @param filterFrom  filter to batches started after this datetime, format is 2019-01-01T00:00:00
     * @param filterUntil filter to batches finished before this datetime, format is 2019-12-31T23:59:59
     * @param filterState filter to batches with this state (possible values are PLANNED, RUNNING, FINISHED, KILLED or FAILED)
     * @return
     */
    @GET
    @Path("batches")
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    public Response getBatches(
            @QueryParam("offset") String offsetStr,
            @QueryParam("limit") String limitStr,
            @QueryParam("owner") String filterOwner,
            @QueryParam("from") String filterFrom,
            @QueryParam("until") String filterUntil,
            @QueryParam("state") String filterState
    ) {
        try {
            //access control with basic access authentication (deprecated)
            checkAccessControlByBasicAccessAuth();

            //authentication
            AuthenticatedUser user = getAuthenticatedUserByOauth();
            String role = ROLE_READ_PROCESSES;
            if (!user.getRoles().contains(role)) {
                throw new ForbiddenException("user '%s' is not allowed to manage processes (missing role '%s')", user.getName(), role); //403
            }

            //offset & limit
            int offset = GET_BATCHES_DEFAULT_OFFSET;
            if (StringUtils.isAnyString(offsetStr)) {
                try {
                    offset = Integer.valueOf(offsetStr);
                    if (offset < 0) {
                        throw new BadRequestException("offset must be zero or a positive number, '%s' is not", offsetStr);
                    }
                } catch (NumberFormatException e) {
                    throw new BadRequestException("offset must be a number, '%s' is not", offsetStr);
                }
            }
            int limit = GET_BATCHES_DEFAULT_LIMIT;
            if (StringUtils.isAnyString(limitStr)) {
                try {
                    limit = Integer.valueOf(limitStr);
                    if (limit < 1) {
                        throw new BadRequestException("limit must be a positive number, '%s' is not", limitStr);
                    }
                } catch (NumberFormatException e) {
                    throw new BadRequestException("limit must be a number, '%s' is not", limitStr);
                }
            }

            //filter
            Filter filter = new Filter();
            if (StringUtils.isAnyString(filterOwner)) {
                filter.owner = filterOwner;
            }
            if (StringUtils.isAnyString(filterFrom)) {
                filter.from = parseLocalDateTime(filterFrom);
            }
            if (StringUtils.isAnyString(filterUntil)) {
                filter.until = parseLocalDateTime(filterUntil);
            }
            if (StringUtils.isAnyString(filterState)) {
                filter.stateCode = toBatchStateCode(filterState);
            }

            //response size
            int totalSize = this.processManager.getBatchesCount(filter);
            JSONObject result = new JSONObject();
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("total_size", totalSize);

            //batch & process data
            List<ProcessInBatch> pibs = this.processManager.getProcessesInBatches(filter, offset, limit);
            List<BatchWithProcesses> batchWithProcesses = extractBatchesWithProcesses(pibs);
            JSONArray batchesJson = new JSONArray();
            for (BatchWithProcesses batch : batchWithProcesses) {
                JSONObject batchJson = batchToJson(batch);
                batchesJson.put(batchJson);
            }
            result.put("batches", batchesJson);
            return Response.ok().entity(result.toString()).build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    private void checkAccessControlByBasicAccessAuth() {
        boolean disabled = true;
        if (!disabled) {
            String loggedUserKey = findLoggedUserKey();
            User user = this.loggedUsersSingleton.getUser(loggedUserKey);
            if (user == null) {
                throw new UnauthorizedException("user==null"); //401
            }
            boolean allowed = rightsResolver.isActionAllowed(user, SecuredActions.MANAGE_LR_PROCESS.getFormalName(), SpecialObjects.REPOSITORY.getPid(), null, ObjectPidsPath.REPOSITORY_PATH).flag();
            if (!allowed) {
                throw new ForbiddenException("user '%s' is not allowed to manage processes", user.getLoginname()); //403
            }
        }
    }

    /**
     * Schedules new process
     *
     * @param processDefinition
     * @return
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response scheduleProcess(JSONObject processDefinition) {
        try {
            String processAuthToken = getProcessAuthToken();
            if (processDefinition == null) {
                throw new BadRequestException("missing process definition");
            }
            if (!processDefinition.has("defid")) {
                throw new BadRequestException("empty defid");
            }
            String defid = processDefinition.getString("defid");
            JSONObject params = new JSONObject();
            if (processDefinition.has("params")) {
                params = processDefinition.getJSONObject("params");
            }
            //authentication & authorization
            ClientAuthHeaders clientAuthHeaders = extractClientAuthHeaders();
            if (processAuthToken != null) { //run by process (so the new process will be it's sibling in same batch)
                //System.out.println("process auth token found");
                ProcessManager.ProcessAboutToScheduleSibling originalProcess = processManager.getProcessAboutToScheduleSiblingByAuthToken(processAuthToken);
                if (originalProcess == null) {
                    throw new UnauthorizedException("invalid token"); //401
                }
                String userId = originalProcess.getOwnerId();
                String userName = originalProcess.getOwnerName();
                String batchToken = originalProcess.getBatchToken();
                String newProcessAuthToken = UUID.randomUUID().toString();
                List<String> paramsList = new ArrayList<>();
                paramsList.addAll(paramsToList(defid, params, clientAuthHeaders));
                return scheduleProcess(defid, paramsList, userId, userName, batchToken, newProcessAuthToken);
            } else { //run by user (through web client)
                //System.out.println("process auth token NOT found");
                String batchToken = UUID.randomUUID().toString();
                List<String> paramsList = new ArrayList<>();
                String newProcessAuthToken = UUID.randomUUID().toString();
                paramsList.addAll(paramsToList(defid, params, clientAuthHeaders));
                //authentication
                AuthenticatedUser user = getAuthenticatedUserByOauth();
                //authorization
                String role = ROLE_SCHEDULE_PROCESSES;
                if (!user.getRoles().contains(role)) {
                    throw new ForbiddenException("user '%s' is not allowed to manage processes (missing role '%s')", user.getName(), role); //403
                }
                return scheduleProcess(defid, paramsList, user.getId(), user.getName(), batchToken, newProcessAuthToken);
            }
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    private Response scheduleProcess(String defid, List<String> params, String ownerId, String ownerName, String batchToken, String newProcessAuthToken) {
        LRProcess newProcess = processSchedulingHelper.scheduleProcess(defid, params, ownerId, ownerName, batchToken, newProcessAuthToken);
        URI uri = UriBuilder.fromResource(LRResource.class).path("{uuid}").build(newProcess.getUUID());
        return Response.created(uri).entity(lrPRocessToJSONObject(newProcess).toString()).build();
    }

    private JSONObject lrPRocessToJSONObject(LRProcess lrProcess) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("uuid", lrProcess.getUUID());
        jsonObject.put("pid", lrProcess.getPid()); //empty
        jsonObject.put("id", lrProcess.getDefinitionId());
        jsonObject.put("state", lrProcess.getProcessState().toString());
        //jsonObject.put("batchState", lrProcess.getBatchState().toString());
        jsonObject.put("name", lrProcess.getProcessName());
        if (lrProcess.getPlannedTime() > 0) {
            jsonObject.put("planned", Utils.toFormattedStringOrNull(lrProcess.getPlannedTime()));
        }
        jsonObject.put("userid", lrProcess.getLoginname()); //empty
        jsonObject.put("userFirstname", lrProcess.getFirstname()); //empty
        jsonObject.put("userSurname", lrProcess.getSurname()); //empty
        return jsonObject;
    }

    private List<String> paramsToList(String id, JSONObject params, ClientAuthHeaders clientAuthHeaders) {
        switch (id) {
            case "new_process_api_test": {
                //duration (of every process in the batch) in seconds
                Integer duration = extractOptionalParamPositiveInteger(params, "duration", 1);
                //number of processes in the batch
                Integer processesInBatch = extractOptionalParamPositiveInteger(params, "processesInBatch", 1);
                //final state of every process in the batch (including random)
                String finalState = extractOptionalParamWithValueFromEnum(params, "finalState", ProcessApiTestProcess.FinalState.class, ProcessApiTestProcess.FinalState.FINISHED.name());

                List<String> result = new ArrayList<>();
                //Kramerius APIs
                result.addAll(processSchedulingHelper.processParamsKrameriusAdminApiCredentials(clientAuthHeaders));//protoze spousti podprocesy
                //test process params
                result.add(duration.toString());
                result.add(processesInBatch.toString());
                result.add(finalState);
                return result;
            }
            case "new_indexer_index_object": {
                String type = extractMandatoryParamWithValueFromEnum(params, "type", IndexationType.class);
                String pid = extractMandatoryParamWithValuePrefixed(params, "pid", "uuid:");
                Boolean ignoreInconsistentObjects = extractOptionalParamBoolean(params, "ignoreInconsistentObjects", false);
                String title = extractOptionalParamString(params, "title", null);

                List<String> result = new ArrayList<>();
                //Kramerius APIs
                result.addAll(processSchedulingHelper.processParamsKrameriusAdminApiCredentials(clientAuthHeaders)); //pro pristup k repozitari pres verejne rest api
                //Solr
                result.addAll(processSchedulingHelper.processParamsSolr());
                //indexation params
                result.add(type);//indexation type
                result.add(pid);//indexation's root pid
                result.add(ignoreInconsistentObjects.toString());
                result.add(title);//indexation's root title
                return result;
            }
            case "new_indexer_index_model": {
                String type = extractMandatoryParamWithValueFromEnum(params, "type", IndexationType.class);
                String pid = extractMandatoryParamWithValuePrefixed(params, "pid", "model:");
                Boolean ignoreInconsistentObjects = extractOptionalParamBoolean(params, "ignoreInconsistentObjects", false);
                Boolean indexNotIndexed = extractOptionalParamBoolean(params, "indexNotIndexed", true);
                Boolean indexRunningOrError = extractOptionalParamBoolean(params, "indexRunningOrError", false);
                Boolean indexIndexedOutdated = extractOptionalParamBoolean(params, "indexIndexedOutdated", false);
                Boolean indexIndexed = extractOptionalParamBoolean(params, "indexIndexed", false);

                List<String> result = new ArrayList<>();
                //Kramerius APIs
                result.addAll(processSchedulingHelper.processParamsKrameriusAdminApiCredentials(clientAuthHeaders));//pro pristup k repozitari pres verejne rest api
                //Solr
                result.addAll(processSchedulingHelper.processParamsSolr());
                //indexation params
                result.add(type); //indexation type
                result.add(pid); //indexation's root pid
                result.add(ignoreInconsistentObjects.toString());
                result.add(indexNotIndexed.toString()); //if not-indexed objects should be indexed
                result.add(indexRunningOrError.toString());//if running-or-error objects should be indexed
                result.add(indexIndexedOutdated.toString());//if indexed-outdated objects should be indexed
                result.add(indexIndexed.toString());//if indexed objects should be indexed
                return result;
            }
            case "set_policy": {
                String scope = extractMandatoryParamWithValueFromEnum(params, "scope", SetPolicyProcess.Scope.class);
                String policy = extractMandatoryParamWithValueFromEnum(params, "policy", SetPolicyProcess.Policy.class);
                String pid = extractMandatoryParamWithValuePrefixed(params, "pid", "uuid:");
                String title = extractOptionalParamString(params, "title", null);

                List<String> result = new ArrayList<>();
                //Kramerius APIs
                result.addAll(processSchedulingHelper.processParamsKrameriusAdminApiCredentials(clientAuthHeaders));//pro pristup k repozitari pres verejne rest api
                //set-policy params
                result.add(scope);
                result.add(policy);
                result.add(pid);
                result.add(title);
                return result;
            }
            default: {
                throw new BadRequestException("unsupported process id '%s'", id);
            }
        }
    }

    private Integer extractOptionalParamPositiveInteger(JSONObject params, String paramName, int defaultValue) {
        if (params.has(paramName)) {
            try {
                int processesInBatch = params.getInt(paramName);
                if (processesInBatch < 1) {
                    throw new BadRequestException("invalid value (not a positive number) of %s: '%d'", paramName, processesInBatch);
                } else {
                    return processesInBatch;
                }
            } catch (JSONException e) {
                throw new BadRequestException("invalid value (not a number) of %s: '%s'", paramName, params.get(paramName));
            }
        } else {
            return defaultValue;
        }
    }

    private String extractOptionalParamString(JSONObject params, String paramName, String defaultValue) {
        return params.has(paramName) ? params.getString(paramName) : defaultValue;
    }

    private Boolean extractOptionalParamBoolean(JSONObject params, String paramName, boolean defaultValue) {
        if (params.has(paramName)) {
            return params.getBoolean(paramName);
        } else {
            return defaultValue;
        }
    }

    private String extractMandatoryParamWithValuePrefixed(JSONObject params, String paramName, String prefix) {
        String value = extractOptionalParamString(params, paramName, null);
        if (value == null) {
            throw new BadRequestException("missing mandatory parameter %s: ", paramName);
        } else {
            if (!value.toLowerCase().startsWith(prefix)) {
                throw new BadRequestException("invalid value of %s (doesn't start with '%s') : '%s'", paramName, prefix, value);
            }
            return value;
        }
    }

    private String extractOptionalParamWithValueFromEnum(JSONObject params, String paramName, Class enumClass, String defaultValue) {
        String value = extractOptionalParamString(params, paramName, defaultValue);
        if (value == null) {
            return null;
        } else {
            Object[] enumConstants = enumClass.getEnumConstants();
            for (int i = 0; i < enumConstants.length; i++) {
                String enumValue = enumConstants[i].toString();
                if (value.equals(enumValue)) {
                    return value;
                }
            }
            throw new BadRequestException("invalid value of %s: '%s'", paramName, value);
        }
    }

    private String extractMandatoryParamWithValueFromEnum(JSONObject params, String paramName, Class enumClass) {
        String value = extractOptionalParamString(params, paramName, null);
        if (value == null) {
            throw new BadRequestException("missing mandatory parameter %s: ", paramName);
        } else {
            Object[] enumConstants = enumClass.getEnumConstants();
            for (int i = 0; i < enumConstants.length; i++) {
                String enumValue = enumConstants[i].toString();
                if (value.equals(enumValue)) {
                    return value;
                }
            }
            throw new BadRequestException("invalid value of %s: '%s'", paramName, value);
        }
    }

    //TODO: proverit fungovani
    private SecuredActions securedAction(String processType, LRProcessDefinition definition) {
        return definition.getSecuredAction() != null ? SecuredActions.findByFormalName(definition.getSecuredAction()) : SecuredActions.findByFormalName(processType);
    }

    private LocalDateTime parseLocalDateTime(String string) {
        if (string == null) {
            return null;
        } else {
            try {
                return LocalDateTime.parse(string, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("incorrect date-time format '%s'", string);
            }
        }
    }

    private List<BatchWithProcesses> extractBatchesWithProcesses(List<ProcessInBatch> allPibs) {
        Map<String, List<ProcessInBatch>> pibListByToken = new HashMap<>();
        for (ProcessInBatch pib : allPibs) {
            if (!pibListByToken.containsKey(pib.batchToken)) {
                pibListByToken.put(pib.batchToken, new ArrayList<>());
            }
            List<ProcessInBatch> pibList = pibListByToken.get(pib.batchToken);
            pibList.add(pib);
        }
        List<BatchWithProcesses> result = new ArrayList<>(pibListByToken.size());
        for (List<ProcessInBatch> pibsOfBatch : pibListByToken.values()) {
            ProcessInBatch firstPib = pibsOfBatch.get(0);
            BatchWithProcesses batchWithProcesses = new BatchWithProcesses();
            batchWithProcesses.token = firstPib.batchToken;
            batchWithProcesses.firstProcessId = firstPib.batchId;
            batchWithProcesses.stateCode = firstPib.batchStateCode;
            batchWithProcesses.planned = firstPib.batchPlanned;
            batchWithProcesses.started = firstPib.batchStarted;
            batchWithProcesses.finished = firstPib.batchFinished;
            batchWithProcesses.ownerId = firstPib.batchOwnerId;
            batchWithProcesses.ownerName = firstPib.batchOwnerName;
            for (ProcessInBatch pib : pibsOfBatch) {
                Process process = new Process();
                process.uuid = pib.processUuid;
                process.id = pib.processId;
                process.defid = pib.processDefid;
                process.name = pib.processName;
                process.stateCode = pib.processStateCode;
                process.planned = pib.processPlanned;
                process.started = pib.processStarted;
                process.finished = pib.processFinished;
                batchWithProcesses.processes.add(process);
            }
            result.add(batchWithProcesses);
        }
        Collections.sort(result, (o1, o2) -> -1 * o1.planned.compareTo(o2.planned));
        return result;
    }

    private JSONObject batchToJson(BatchWithProcesses batchWithProcesses) {
        JSONObject json = new JSONObject();
        //batch
        JSONObject batchJson = new JSONObject();
        batchJson.put("token", batchWithProcesses.token);
        batchJson.put("id", batchWithProcesses.firstProcessId);
        batchJson.put("state", toBatchStateName(batchWithProcesses.stateCode));
        batchJson.put("planned", Utils.toFormattedStringOrNull(batchWithProcesses.planned));
        batchJson.put("started", Utils.toFormattedStringOrNull(batchWithProcesses.started));
        batchJson.put("finished", Utils.toFormattedStringOrNull(batchWithProcesses.finished));
        batchJson.put("owner_id", batchWithProcesses.ownerId);
        batchJson.put("owner_name", batchWithProcesses.ownerName);
        json.put("batch", batchJson);
        //processes
        JSONArray processArray = new JSONArray();
        for (Process process : batchWithProcesses.processes) {
            JSONObject processJson = new JSONObject();
            processJson.put("id", process.id);
            processJson.put("uuid", process.uuid);
            processJson.put("defid", process.defid);
            processJson.put("name", process.name);
            processJson.put("state", toProcessStateName(process.stateCode));
            processJson.put("planned", Utils.toFormattedStringOrNull(process.planned));
            processJson.put("started", Utils.toFormattedStringOrNull(process.started));
            processJson.put("finished", Utils.toFormattedStringOrNull(process.finished));
            processArray.put(processJson);
        }
        json.put("processes", processArray);
        return json;
    }


    private String toProcessStateName(Integer stateCode) {
        switch (stateCode) {
            case 0:
                return "NOT_RUNNING";
            case 1:
                return "RUNNING";
            case 2:
                return "FINISHED";
            case 3:
                return "FAILED";
            case 4:
                return "KILLED";
            case 5:
                return "PLANNED";
            case 9:
                return "WARNING";
            default:
                return "UNKNOWN";
        }
    }

    private String toBatchStateName(Integer batchStateCode) {
        switch (batchStateCode) {
            case 0:
                return "PLANNED";
            case 1:
                return "RUNNING";
            case 2:
                return "FINISHED";
            case 3:
                return "FAILED";
            case 4:
                return "KILLED";
            default:
                return "UNKNOWN";
        }
    }

    private int toBatchStateCode(String batchStateName) {
        switch (batchStateName) {
            case "PLANNED":
                return 0;
            case "RUNNING":
                return 1;
            case "FINISHED":
                return 2;
            case "FAILED":
                return 3;
            case "KILLED":
                return 4;
            default:
                throw new BadRequestException("unknown state '%s'", batchStateName);
        }
    }

    private boolean isDeletableState(String batchState) {
        String[] deletableStates = new String[]{"FINISHED", "FAILED", "KILLED"};
        return batchState != null && Arrays.asList(deletableStates).contains(batchState);
    }

    private boolean isKillableState(String batchState) {
        String[] deletableStates = new String[]{"PLANNED", "RUNNING"};
        return batchState != null && Arrays.asList(deletableStates).contains(batchState);
    }

    public static final class BatchWithProcesses extends Batch {
        List<Process> processes = new ArrayList<>();
    }

    public static final class Process {
        public String id;
        public String uuid;
        public String defid;
        public String name;
        public Integer stateCode;
        public LocalDateTime planned;
        public LocalDateTime started;
        public LocalDateTime finished;
    }
}
