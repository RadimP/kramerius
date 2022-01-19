/*
 * Copyright (C) 2013 Pavel Stastny
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
package cz.incad.kramerius.rest.api.k5.client.feeder.decorators;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Element;

import com.google.inject.Inject;

import cz.incad.kramerius.SolrAccess;
import cz.incad.kramerius.rest.api.exceptions.GenericApplicationException;
import cz.incad.kramerius.rest.api.k5.client.SolrMemoization;
import cz.incad.kramerius.rest.api.k5.client.item.utils.ItemResourceUtils;
import cz.incad.kramerius.rest.api.k5.client.utils.SOLRUtils;

/**
 * Doplni titulky z indexu
 * 
 * @author pavels
 */
public class FeederSolrTitleDecorate extends AbstractFeederDecorator {

    public static final Logger LOGGER = Logger
            .getLogger(FeederSolrTitleDecorate.class.getName());

    public static final String SOLR_TITLE_KEY = AbstractFeederDecorator
            .key("TITLE");


    @Inject
    SolrMemoization memo;

    @Override
    public String getKey() {
        return SOLR_TITLE_KEY;
    }

    @Override
    public void decorate(JSONObject jsonObject, Map<String, Object> context) {
        try {

            String pid = jsonObject.getString("pid");

            Element doc = this.memo.getRememberedIndexedDoc(pid);
            if (doc == null)
                doc = this.memo.askForIndexDocument(pid);

            if (doc != null) {
                String title = SOLRUtils.value(doc, "title.search", String.class);
                if (title != null) {
                    jsonObject.put("title", ItemResourceUtils.preventAutomaticConversion(title));
                }
                String root_title = SOLRUtils.value(doc, "root.title",
                        String.class);
                if (root_title != null) {
                    jsonObject.put("root.title", ItemResourceUtils.preventAutomaticConversion(root_title));
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw new GenericApplicationException(e.getMessage());
        } catch (JSONException e) {
            throw new GenericApplicationException(e.getMessage());
        }
    }

    @Override
    public boolean apply(JSONObject jsonObject, String context) {
        TokenizedPath tpath = super.feederContext(tokenize(context));
        return tpath.isParsed();
    }

}
