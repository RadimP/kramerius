package cz.kramerius.searchIndex;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import cz.incad.kramerius.fedora.RepoModule;
import cz.incad.kramerius.processes.starter.ProcessStarter;
import cz.incad.kramerius.processes.utils.ProcessUtils;
import cz.incad.kramerius.resourceindex.ResourceIndexModule;
import cz.incad.kramerius.solr.SolrModule;
import cz.incad.kramerius.statistics.NullStatisticsModule;
import cz.kramerius.adapters.FedoraAccess;
import cz.kramerius.adapters.IResourceIndex;
import cz.kramerius.searchIndex.indexer.SolrConfig;
import cz.kramerius.searchIndex.indexerProcess.IndexationType;
import cz.kramerius.searchIndex.indexerProcess.Indexer;
import cz.kramerius.searchIndex.indexerProcess.ProgressListener;
import cz.kramerius.searchIndex.repositoryAccess.KrameriusRepositoryAccessAdapter;
import cz.kramerius.searchIndex.repositoryAccessImpl.krameriusNewApi.ResourceIndexImplByKrameriusNewApis;
import cz.kramerius.searchIndex.repositoryAccessImpl.krameriusNoApi.RepositoryAccessImplByKrameriusDirect;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Deklarace procesu je v shared/common/src/main/java/cz/incad/kramerius/processes/res/lp.st (new_indexer_index_object)
 */
public class NewIndexerProcessIndexObject {

    public static final Logger LOGGER = Logger.getLogger(NewIndexerProcessIndexObject.class.getName());

    public static final String API_AUTH_HEADER_AUTH_TOKEN = "process-auth-token";

    public static void main(String[] args) throws IOException {
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
        //SOLR
        String solrBaseUrl = args[argsIndex++];
        String solrCollection = args[argsIndex++];
        boolean solrUseHttps = Boolean.valueOf(args[argsIndex++]);
        String solrLogin = args[argsIndex++];
        String solrPassword = args[argsIndex++];
        //indexation info
        String type = args[argsIndex++];
        String pid = args[argsIndex++];
        Boolean ignoreInconsistentObjects = Boolean.valueOf(args[argsIndex++]);
        //tady je problem v tom, ze pokud jeden z parametru obsahuje carku, tak Kramerius pri parsovani argumentu z pole v databazi to vyhodnoti jako vice argumentu.
        //napr.
        //["TREE_AND_FOSTER_TREES", "uuid:23345cf7-7e62-47e9-afad-018624a19ea6", "Quartet A minor, op. 51, no. 2. Andante moderato"] se pri registraci procesu ulozi a po jeho spusteni nacte jako:
        //["TREE_AND_FOSTER_TREES", "uuid:23345cf7-7e62-47e9-afad-018624a19ea6", "Quartet A minor", " op. 51", " no. 2. Andante moderato"]
        //proto nazev, co muze obsahovat carku, pouzivam jako posledni argument
        String title = mergeArraysEnd(args, argsIndex);

        //zmena nazvu
        //TODO: mozna spis abstraktni proces s metodou updateName() a samotny kod procesu by mel callback na zjisteni nazvu, kterym by se zavolal updateName()
        //ProcessStarter.updateName(String.format("Indexace (objekt %s, typ %s)", pid, type));
        if (title != null) {
            ProcessStarter.updateName(String.format("Indexace %s (%s, typ %s)", title, pid, type));
        } else {
            ProcessStarter.updateName(String.format("Indexace %s (typ %s)", pid, type));
        }

        SolrConfig solrConfig = new SolrConfig(solrBaseUrl, solrCollection, solrUseHttps, solrLogin, solrPassword);

        //access to repository through new public HTTP APIs
        /*RepositoryAccessImplByKrameriusNewApis.Credentials krameriusCredentials = new RepositoryAccessImplByKrameriusNewApis.Credentials(krameriusApiAuthClient, krameriusApiAuthUid, krameriusApiAuthAccessToken);
        FedoraAccess repository = new RepositoryAccessImplByKrameriusNewApis(krameriusBackendBaseUrl, krameriusCredentials);*/

        //access to repository through java directly (injected cz.incad.kramerius.FedoraAccess)
        Injector injector = Guice.createInjector(new SolrModule(), new ResourceIndexModule(), new RepoModule(), new NullStatisticsModule());
        cz.incad.kramerius.FedoraAccess rawRepositoryAccess = injector.getInstance(Key.get(cz.incad.kramerius.FedoraAccess.class, Names.named("rawFedoraAccess")));
        FedoraAccess repository = new RepositoryAccessImplByKrameriusDirect(rawRepositoryAccess);

        //access to resource index through new public APIs
        IResourceIndex resourceIndex = new ResourceIndexImplByKrameriusNewApis(ProcessUtils.getCoreBaseUrl());

        KrameriusRepositoryAccessAdapter repositoryAdapter = new KrameriusRepositoryAccessAdapter(repository, resourceIndex);
        Indexer indexer = new Indexer(repositoryAdapter, solrConfig, System.out, ignoreInconsistentObjects);
        indexer.indexByObjectPid(pid, IndexationType.valueOf(type), new ProgressListener() {
            @Override
            public void onProgress(int processed) {
                //log number of objects processed so far
                if (processed < 100 && processed % 10 == 0 ||
                        processed < 1000 && processed % 100 == 0 ||
                        processed % 1000 == 0
                ) {
                    LOGGER.info("objects processed so far: " + processed);
                }
            }

            @Override
            public void onFinished(int processed) {
            }
        });
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
}
