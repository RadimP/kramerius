package cz.incad.Kramerius.exts.menu.main.impl.adm.items;

import java.io.IOException;

import cz.incad.Kramerius.exts.menu.main.impl.AbstractMainMenuItem;
import cz.incad.Kramerius.exts.menu.main.impl.adm.AdminMenuItem;
import cz.incad.kramerius.security.SecuredActions;

public class ParametrizedSDNNTShowResult   extends AbstractMainMenuItem implements AdminMenuItem {

    public ParametrizedSDNNTShowResult() {}

    @Override
    public boolean isRenderable() {

        return (hasUserAllowedAction(SecuredActions.ADMINISTRATE.getFormalName()) ||
                hasUserAllowedAction(SecuredActions.DNNT_ADMIN.getFormalName()) ||
                hasUserAllowedPlanProcess("dnntset")
        );


    }
    @Override
    public String getRenderedItem() throws IOException {
        return renderMainMenuItem(
                "javascript:showSdnntSync(); javascript:hideAdminMenu();",
                "administrator.menu.dialogs.sdnntsync.title", false);
    }
}