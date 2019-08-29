package org.oskari.print.util;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import fi.nls.oskari.util.IOHelper;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPatternContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.oskari.print.PDF;
import org.oskari.print.request.PDPrintStyle;
import fi.nls.oskari.util.JSONHelper;

import javax.imageio.ImageIO;

public class StyleUtil {
    private static final String SVG_MARKERS_JSON = "svg-markers.json";
    private static final float PNG_SIZE = 32f;
    public static final String OSKARI_DEFAULT = "default";
    public static final String STYLES_JSON_KEY = "styles";

    public static final Map<String, Integer> LINE_CAP_STYLE  = new HashMap<String, Integer>() {{
        put("butt",0);
        put("round", 1);
        put("square", 2);
    }};
    public static final Map<String, Integer> LINE_JOIN_STYLE  = new HashMap<String, Integer>() {{
        put("mitre",0);
        put("miter",0);
        put("round", 1);
        put("bevel", 2);
    }};

    public static PDPrintStyle getLineStyle (JSONObject oskariStyle) {
        PDPrintStyle style = new PDPrintStyle();
        JSONObject stroke = JSONHelper.getJSONObject(oskariStyle, "stroke");
        setStrokeStyle(style, stroke);
        // polygon doesn't have cap style
        style.setLineCap(LINE_CAP_STYLE.get(JSONHelper.optString(stroke,"lineCap")));
        style.setLabelProperty(getLabelStyle(oskariStyle));
        return style;
    }
    public static PDPrintStyle getPolygonStyle (JSONObject oskariStyle, PDResources resources) throws IOException {
        PDPrintStyle style = new PDPrintStyle();
        JSONObject area = JSONHelper.getJSONObject(JSONHelper.getJSONObject(oskariStyle, "stroke"), "area");
        setStrokeStyle(style, area);

        JSONObject fill = JSONHelper.getJSONObject(oskariStyle, "fill");
        int pattern = JSONHelper.getJSONObject(fill, "area").optInt("pattern");
        Color color = ColorUtil.parseColor(JSONHelper.optString(fill,"color"));
        style.setFillColor(color);

        if (pattern >= 0 && pattern <= 3 && color != null) {
            style.setFillPattern(createFillPattern(resources, pattern, color));
        }
        style.setLabelProperty(getLabelStyle(oskariStyle));
        return style;
    }

    public static PDPrintStyle getPointStyle (JSONObject oskariStyle, PDDocument doc) throws IOException {
        PDPrintStyle style = new PDPrintStyle();
        JSONObject image = JSONHelper.getJSONObject(oskariStyle, "image");
        int shape = image.optInt("shape", 5); // External icons not supported
        String fillColor = JSONHelper.optString(JSONHelper.getJSONObject(image, "fill"), "color");
        style.setFillColor(ColorUtil.parseColor(fillColor));
        int size = image.optInt("size", 3);
        float scale = size < 1 || size > 5 ? 1f : 0.6f +  size /10f;
        JSONObject marker = getMarker(shape);
        double defaultOffset = PNG_SIZE / 2;
        style.setIconOffsetX((float) marker.optDouble("offsetX", defaultOffset) * scale);
        style.setIconOffsetY((float) marker.optDouble("offsetY", defaultOffset) * scale);
        try {
            String markerData = JSONHelper.getString(marker, "data").replace("$fill", fillColor);
            style.setIcon(createIcon(markerData, doc, scale));
        } catch (Exception e) {
            throw new IOException ("Failed to create marker icon: " + shape);
        }
        style.setLabelProperty(getLabelStyle(oskariStyle));
        return style;
    }
    private static void setStrokeStyle (PDPrintStyle style, JSONObject json) {
        float width = (float) json.optDouble("width", 1);
        String lineDash = JSONHelper.optString(json,"lineDash");
        style.setLineWidth(width);
        style.setLineJoin(LINE_JOIN_STYLE.get(JSONHelper.optString(json,"lineJoin")));
        style.setLinePattern(getStrokeDash(lineDash, width));
        style.setLineColor(ColorUtil.parseColor(JSONHelper.optString(json,"color")));
    }
    private static List<String> getLabelStyle (JSONObject oskariStyle) {
        JSONObject text = JSONHelper.getJSONObject(oskariStyle, "text");
        if (text == null) return null;
        Object labelProperty = text.opt("labelProperty");
        if (labelProperty instanceof  JSONArray) {
            return JSONHelper.getArrayAsList((JSONArray) labelProperty);
        }
        if (labelProperty instanceof  String){
            List <String> propList = new ArrayList();
            propList.add((String) labelProperty);
            return propList;
        }
        return null;
    }
    // TODO: get marker data from EnvHelper
    public static JSONObject getMarker (int index) throws IOException {
        try (InputStream is = PDF.class.getResourceAsStream(SVG_MARKERS_JSON)) {
            if (is == null) {
                throw new IOException("Resource file " + SVG_MARKERS_JSON + " does not exist");
            }
            JSONArray svgMarkers = JSONHelper.createJSONArray(IOHelper.readString(is));
            if (index >= svgMarkers.length()) {
                throw new IOException("SVG marker:" + index + " does not exist");
            }
            return JSONHelper.getJSONObject(svgMarkers, index);
        }
    }

    private static  float[] getStrokeDash (String dash, float strokeWidth) {
        float [] pattern;
        switch (dash) {
            case "dash":
                pattern = new float[]{5, 4 + strokeWidth};
                break;
            case "dashdot":
            case "dot":
                pattern = new float[]{1, 1 + strokeWidth};
                break;
            case "longdash":
                pattern = new float[]{10, 4 + strokeWidth};
                break;
            case "longdashdot":
                pattern = new float[]{5, 1 + strokeWidth,  1, 1 + strokeWidth};
                break;
            default:
                pattern = new float[]{};
        }
        return pattern;
    }

    private static PDImageXObject createIcon (String data, PDDocument doc, float scale) throws IOException, TranscoderException {
        byte[] svgBytes = data.getBytes();
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, PNG_SIZE * scale);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, PNG_SIZE * scale);
        TranscoderInput in = new TranscoderInput(new ByteArrayInputStream(svgBytes));
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        TranscoderOutput out = new TranscoderOutput(os);
        transcoder.transcode(in, out);
        BufferedImage bufferedImage = ImageIO.read( new ByteArrayInputStream(os.toByteArray()));
        os.close();
        return LosslessFactory.createFromImage(doc, bufferedImage);
    }

    private static PDColor createFillPattern(PDResources resources, int fillPattern, Color fillColor) throws IOException {
        PDPattern pattern = new PDPattern(null, PDDeviceRGB.INSTANCE);
        PDTilingPattern tilingPattern = new PDTilingPattern();
        int size = 64;
        tilingPattern.setBBox(new PDRectangle(size,size));
        tilingPattern.setPaintType(PDTilingPattern.PAINT_COLORED);
        tilingPattern.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING);
        tilingPattern.setXStep(size);
        tilingPattern.setYStep(size);
        COSName patternName = resources.add(tilingPattern);
        // thin 1 thick 2.5
        float lineWidth = fillPattern == 0 || fillPattern == 2 ? 1.0f : 2.5f;
        boolean isHorizontal = fillPattern == 2 || fillPattern == 3;
        float numberOfStripes = 9;
        if (lineWidth > 2) {
            numberOfStripes = isHorizontal ? 8 : 6;
        }
        float bandWidth = size / numberOfStripes;
        try (PDPatternContentStream pcs = new PDPatternContentStream(tilingPattern))
        {
            // Set color, draw diagonal line + 2 more diagonals so that corners look good
            pcs.setStrokingColor(fillColor);
            pcs.setLineWidth(lineWidth);
            pcs.setLineCapStyle(LINE_CAP_STYLE.get("square"));
            float limit = isHorizontal ? numberOfStripes : numberOfStripes * 2;
            for (int i = 0 ; i < limit; i ++) {
                float transition = i * bandWidth+ bandWidth/2;
                if (isHorizontal)  {
                    pcs.moveTo(0, transition);
                    pcs.lineTo(size, transition );
                } else {
                    pcs.moveTo(0, size - transition);
                    pcs.lineTo(transition, size);
                }
                pcs.stroke();
            }
        }
        return new PDColor(patternName, pattern);
    }

    public static JSONObject getDefaultOskariStyle () {
        JSONObject json = new JSONObject();
        // dot
        JSONObject image = new JSONObject();
        JSONObject imageFill = new JSONObject();
        JSONHelper.putValue(imageFill, "color", "#FAEBD7");
        JSONHelper.putValue(image, "fill", imageFill);
        JSONHelper.putValue(image, "shape", 5);
        JSONHelper.putValue(image, "size", 3);
        JSONHelper.putValue(json, "image", image);
        // line
        JSONObject stroke = new JSONObject();
        JSONHelper.putValue(stroke, "color", "#000000");
        JSONHelper.putValue(stroke, "width",1);
        JSONHelper.putValue(stroke, "lineDash", "solid");
        JSONHelper.putValue(stroke, "lineCap", "butt" );
        JSONHelper.putValue(stroke, "lineJoin", "mitre");
        // area
        JSONObject strokeArea = new JSONObject();
        JSONHelper.putValue(strokeArea, "color", "#000000");
        JSONHelper.putValue(strokeArea, "width", 1);
        JSONHelper.putValue(strokeArea, "lineDash", "solid");
        JSONHelper.putValue(strokeArea, "lineJoin", "mitre");
        JSONHelper.putValue(stroke, "area", strokeArea);
        JSONHelper.putValue(json, "stroke", stroke);
        JSONObject fill = new JSONObject();
        JSONHelper.putValue(fill, "color", "#FAEBD7");
        JSONObject fillArea = new JSONObject();
        JSONHelper.putValue(fillArea, "pattern", -1);
        JSONHelper.putValue(fill, "area", fillArea);
        JSONHelper.putValue(json, "fill", fill);
        return json;
    }
}
