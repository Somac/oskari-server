package org.oskari.statistics.plugins.unsd;

import fi.nls.oskari.control.statistics.data.IndicatorValue;
import fi.nls.oskari.control.statistics.data.StatisticalIndicator;
import fi.nls.oskari.control.statistics.data.StatisticalIndicatorDataModel;
import fi.nls.oskari.control.statistics.data.StatisticalIndicatorLayer;
import fi.nls.oskari.control.statistics.plugins.StatisticalDatasourcePlugin;
import fi.nls.oskari.control.statistics.plugins.db.DatasourceLayer;
import fi.nls.oskari.control.statistics.plugins.db.StatisticalDatasource;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.log.Logger;
import fi.nls.oskari.util.JSONHelper;
import fi.nls.oskari.util.PropertyUtil;
import org.json.JSONObject;

import java.util.*;
import java.util.stream.Collectors;

public class UnsdStatisticalDatasourcePlugin extends StatisticalDatasourcePlugin {
    private final static Logger LOG = LogFactory.getLogger(UnsdStatisticalDatasourcePlugin.class);

    private UnsdConfig config;
    private UnsdIndicatorParser parser;
    private UnsdDataParser indicatorValuesFetcher;
    private RegionMapper regionMapper;

    /**
     * Maps the UNSD area codes to Oskari layers.
     */
    private Map<Long, String[]> layerAreaCodes = new HashMap<>();

    @Override
    public void update() {
        // get the indicator listing
        UnsdRequest request = new UnsdRequest(config);
        request.setGoal(config.getGoal());
        String targetsResponse = request.getTargets();
        List<StatisticalIndicator> indicators = UnsdIndicatorParser.parseIndicators(targetsResponse);

        // all indicators under goal have same dimensions
        String dimensions = request.getDimensions();
        for (StatisticalIndicator ind : indicators) {
            request.setIndicator(ind.getId());
            // we parse it multiple times to make copies
            ind.setDataModel(UnsdIndicatorParser.parseDimensions(dimensions));
            // generate time period based on config. The datasource doesn't tell which years it has data for :(
            ind.getDataModel().addDimension(
                    UnsdIndicatorParser.generateTimePeriod(
                            config.getTimeVariableId(), config.getTimePeriod()));
            ind.getDataModel().setTimeVariable(config.getTimeVariableId());

            JSONObject dataResponse = JSONHelper.createJSONObject(request.getIndicatorData(null));
            ind.setSource(UnsdIndicatorParser.parseSource(dataResponse));
            getSource().getLayers().stream().forEach(l -> ind.addLayer(l));
            onIndicatorProcessed(ind);
        }
        LOG.info("Parsed indicator response.");
    }

    @Override
    public void init(StatisticalDatasource source) {
        super.init(source);
        config = new UnsdConfig(source.getConfigJSON(), source.getId());
        parser = new UnsdIndicatorParser();
        indicatorValuesFetcher = new UnsdDataParser(config);
        regionMapper = new RegionMapper();
        // optimization for getting data just for the countries we are showing
        initAreaCodes(source.getLayers());
    }

    private void initAreaCodes(List<DatasourceLayer> layers) {
        // TODO; Get codes from RegionSetHelper?
        String[] regionWhitelist = PropertyUtil.getCommaSeparatedList("unsd.region.whitelist");
        if (regionWhitelist.length == 0) {
            // no whitelist -> get data for all regions
            return;
        }

        List<String> countries = Arrays.stream(regionWhitelist)
                .map(code -> regionMapper.find(code))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(c -> c.getCode(CountryRegion.Type.M49))
                .collect(Collectors.toList());

        for (DatasourceLayer layer : layers) {
            layerAreaCodes.put(layer.getMaplayerId(), countries.toArray(new String[0]));
        }
    }

    @Override
    public Map<String, IndicatorValue> getIndicatorValues(
            StatisticalIndicator indicator,
            StatisticalIndicatorDataModel params,
            StatisticalIndicatorLayer regionset) {

        String[] areaCodes = layerAreaCodes.get(regionset.getOskariLayerId());
        // map m49 codes back to region ids (iso2 etc) before returning
        Map<String, IndicatorValue> values = indicatorValuesFetcher.get(params, indicator.getId(), areaCodes);
        List<CountryRegion> regions = values.keySet().stream()
                .map(m49 -> regionMapper.find(m49))
                .filter(Optional::isPresent)
                .map(Optional::get).collect(Collectors.toList());
        Map<String, IndicatorValue> updated = new HashMap<>();
        regions.stream().forEach(c -> {
            IndicatorValue value = values.get(c.getCode(CountryRegion.Type.M49_WO_LEADING));
            // TODO: check if the region code from layer is iso2 or iso3 or m49
            // Now always assumes iso2
            updated.put(c.getCode(CountryRegion.Type.ISO2), value);
        });

        return updated;
    }
}
