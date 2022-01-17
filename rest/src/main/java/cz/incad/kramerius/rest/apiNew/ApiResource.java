package cz.incad.kramerius.rest.apiNew;

import cz.incad.kramerius.fedora.om.RepositoryException;
import cz.incad.kramerius.repository.KrameriusRepositoryApi;
import cz.incad.kramerius.repository.KrameriusRepositoryApiImpl;
import cz.incad.kramerius.rest.apiNew.exceptions.ApiException;
import cz.incad.kramerius.rest.apiNew.exceptions.BadRequestException;
import cz.incad.kramerius.rest.apiNew.exceptions.InternalErrorException;
import cz.incad.kramerius.rest.apiNew.exceptions.NotFoundException;

import javax.inject.Inject;
import java.io.IOException;
import java.util.regex.Pattern;

public abstract class ApiResource {

    /**
     * převzato z Fedory
     *
     * @see org.kramerius.importmets.convertor.BaseConvertor.PID_PATTERN
     * Striktně pro PID nad UUID by mělo být toto: uuid:[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}
     * Nicméně historicky jsou v repozitářích i PIDy neplatných UUID, tudíž je tady tolerovat, omezení by mělo být jen u importu.
     * Toto rozvolnění způsobí přijetí i ne-objektových PIDů jako uuid:123e4567-e89b-12d3-a456-426655440000_0, uuid:123e4567-e89b-12d3-a456-426655440000_1, ... (stránky z pdf, jen ve vyhledávácím indexu, nemají foxml objekt)
     */
    protected static final Pattern PID_PATTERN = Pattern.compile("([A-Za-z0-9]|-|\\.)+:(([A-Za-z0-9])|-|\\.|~|_|(%[0-9A-F]{2}))+");

    @Inject
    public KrameriusRepositoryApi krameriusRepositoryApi;
    //TODO should be interface, but then guice would need bind(KrameriusRepository.class).to(KrameriusRepositoryApiImpl) somewhere
    //public KrameriusRepositoryApiImpl krameriusRepositoryApi;

    /*
    @Inject
    @Named("securedFedoraAccess")
    private FedoraAccess repository;

    @Inject
    private IResourceIndex resourceIndex;

    private KrameriusRepositoryAccessAdapter repositoryAccess;

    protected final KrameriusRepositoryAccessAdapter getRepositoryAccess() {
        if (repositoryAccess == null) {
            repositoryAccess = new KrameriusRepositoryAccessAdapter(repository, resourceIndex);
        }
        return repositoryAccess;
    }

    protected final void checkObjectExists(String pid) throws ApiException {
        try {
            boolean objectExists = getRepositoryAccess().isObjectAvailable(pid);
            if (!objectExists) {
                throw new NotFoundException("object with pid %s not found in repository", pid);
            }
        } catch (IOException e) {
            throw new InternalErrorException(e.getMessage());
        }
    }*/

    protected final void checkObjectExists(String pid) throws ApiException {
        try {
            boolean exists = krameriusRepositoryApi.isPidAvailable(pid);
            if (!exists) {
                throw new NotFoundException("object %s not found in repository", pid);
            }
        } catch (IOException| RepositoryException e) {
            throw new InternalErrorException(e.getMessage());
        }
    }

    protected final void checkObjectAndDatastreamExist(String pid, String dsId) throws ApiException {
        checkObjectExists(pid);
        try {
            boolean exists = krameriusRepositoryApi.isStreamAvailable(pid, dsId);
            if (!exists) {
                throw new NotFoundException("datastream %s of object %s not found in repository", dsId, pid);
            }
        } catch (RepositoryException | IOException e) {
            e.printStackTrace();
            throw new InternalErrorException(e.getMessage());
        }
    }

    protected final void checkObjectAndDatastreamExist(String pid, KrameriusRepositoryApi.KnownDatastreams ds) throws ApiException {
        checkObjectAndDatastreamExist(pid, ds.toString());
    }


    protected final void checkSupportedObjectPid(String pid) {
        if (!PID_PATTERN.matcher(pid).matches()) {
            throw new BadRequestException("'%s' is not in supported PID format for this operation", pid);
        }
    }
}
