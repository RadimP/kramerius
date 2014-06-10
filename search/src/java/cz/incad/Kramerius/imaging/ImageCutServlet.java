package cz.incad.Kramerius.imaging;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import javax.xml.xpath.XPathExpressionException;

import org.json.JSONException;
import org.json.JSONObject;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;

import cz.incad.Kramerius.AbstractImageServlet;
import cz.incad.kramerius.utils.ApplicationURL;
import cz.incad.kramerius.utils.conf.KConfiguration;
import cz.incad.kramerius.utils.imgs.ImageMimeType;
import cz.incad.kramerius.utils.imgs.KrameriusImageSupport;
import cz.incad.kramerius.utils.imgs.KrameriusImageSupport.ScalingMethod;

public class ImageCutServlet extends AbstractImageServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        try {
            String pid = req.getParameter("pid");
            if (pid != null) {
                simpleSubImage(req, resp, pid);
                
            } else {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            }
        } catch (JSONException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } catch (XPathExpressionException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

    }


    
    private void simpleSubImage(HttpServletRequest req,
            HttpServletResponse resp, String pid) throws MalformedURLException, IOException, JSONException, XPathExpressionException {

        BufferedImage bufferedImage = super.rawFullImage(pid,req,0);
        
        String xperct = req.getParameter("xpos");
        String yperct = req.getParameter("ypos");
        String heightperct = req.getParameter("height");
        String widthperct = req.getParameter("width");
        
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        
        int xoffset =(int) (width * Double.parseDouble(xperct));
        int yoffset = (int)(height * Double.parseDouble(yperct));
        
        int cwidth = (int)(width * Double.parseDouble(widthperct));
        int cheight = (int)(height * Double.parseDouble(heightperct));
        
        
        BufferedImage subImage = bufferedImage.getSubimage(Math.max(xoffset,0), Math.max(yoffset,0), Math.min(cwidth, width - xoffset) , Math.min(cheight, height - yoffset));
        KrameriusImageSupport.writeImageToStream(subImage, ImageMimeType.PNG.getDefaultFileExtension(), resp.getOutputStream());
    }
    


    @Override
    public ScalingMethod getScalingMethod() {
        KConfiguration config = KConfiguration.getInstance();
        ScalingMethod method = ScalingMethod.valueOf(config.getProperty(
                "thumbImage.scalingMethod", "BICUBIC_STEPPED"));
        return method;
    }



    @Override
    public boolean turnOnIterateScaling() {
        KConfiguration config = KConfiguration.getInstance();
        boolean highQuality = config.getConfiguration().getBoolean(
                "fullThumbnail.iterateScaling", true);
        return highQuality;
    }
}