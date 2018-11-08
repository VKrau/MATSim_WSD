package org.matsim.plans;


import com.vividsolutions.jts.geom.MultiPolygon;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.opengis.feature.GeometryAttribute;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.io.*;
import java.text.ParseException;
import java.util.*;

public class ParserSHP {

    public static void main(String [] args) throws IOException, ParseException, TransformException, FactoryException {
        ParserSHP runner = new ParserSHP();
        runner.run("input/inputForPlans/shapedistricts.shp");
    }

    // creating the map, where we will store all the Regions
    private final Map<Integer, MultiPolygon> MultiPolygonsMap = new HashMap<>();
    // parsing the shape file
    public Map<Integer, MultiPolygon> run(String shpfile) throws IOException {
        // input file
        File file = new File(shpfile);
        // smth??
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("url", file.toURI().toURL());

        DataStore dataStore = DataStoreFinder.getDataStore(map);
        String typeName = dataStore.getTypeNames()[0];
        MultiPolygon polygon = null;

        FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore
                .getFeatureSource(typeName);
        Filter filter = Filter.INCLUDE; // ECQL.toFilter("BBOX(THE_GEOM, 10,20,30,40)")

        // collect objects
        FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures(filter);

        // here we are parsing all the objects in the shape-file
        try (FeatureIterator<SimpleFeature> features = collection.features()) {
            while (features.hasNext()) {
                SimpleFeature feature = features.next();
                GeometryAttribute defaultGeometryProperty = feature.getDefaultGeometryProperty();
                // if the collected feature is MultiPolygon, the we:
                if (defaultGeometryProperty.getValue() instanceof MultiPolygon){
                    //gets the index features
                    int featureIDInt = ((Long) feature.getAttribute("id")).intValue();
                    polygon = (MultiPolygon) defaultGeometryProperty.getValue();
                    System.out.println(polygon);
                    MultiPolygonsMap.put(featureIDInt, polygon);
                }


            }

        }
        return MultiPolygonsMap;
    }
}