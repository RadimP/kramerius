package cz.incad.kramerius.rest.apiNew.admin.v10;

import com.google.inject.Inject;
import cz.incad.kramerius.rest.apiNew.ConfigManager;
import cz.incad.kramerius.rest.apiNew.exceptions.BadRequestException;
import cz.incad.kramerius.rest.apiNew.exceptions.ForbiddenException;
import cz.incad.kramerius.rest.apiNew.exceptions.InternalErrorException;
import cz.incad.kramerius.utils.StringUtils;
import org.json.JSONObject;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @see cz.incad.kramerius.rest.api.k5.client.info.InfoResource
 */
@Path("/admin/v1.0/config")
public class ConfigResource extends AdminApiResource {

    public static Logger LOGGER = Logger.getLogger(ConfigResource.class.getName());

    @Inject
    private ConfigManager configService;

    //TODO: prejmenovat role podle spravy uctu
    private static final String ROLE_WRITE_CONFIG = "kramerius_admin";
    private static final String ROLE_READ_CONFIG = "kramerius_admin";

    @PUT
    @Path("/{key}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response setProperty(@PathParam("key") String key, String value) {
        try {
            //authentication
            AuthenticatedUser user = getAuthenticatedUserByOauth();
            String role = ROLE_WRITE_CONFIG;
            if (!user.getRoles().contains(role)) {
                throw new ForbiddenException("user '%s' is not allowed to manage config (missing role '%s')", user.getName(), role); //403
            }
            if (!StringUtils.isAnyString(value)) {
                throw new BadRequestException("empty value for key '%s'", key);
            }
            configService.setProperty(key, value);
            return Response.ok().build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

    @GET
    @Path("/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getProperty(@PathParam("key") String key) {
        try {
            //authentication
            AuthenticatedUser user = getAuthenticatedUserByOauth();
            String role = ROLE_WRITE_CONFIG;
            if (!user.getRoles().contains(role)) {
                throw new ForbiddenException("user '%s' is not allowed to manage config (missing role '%s')", user.getName(), role); //403
            }
            String value = configService.getProperty(key);
            JSONObject json = new JSONObject();
            json.put(key, value);
            return Response.ok(json).build();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Throwable e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new InternalErrorException(e.getMessage());
        }
    }

}
