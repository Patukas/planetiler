/*
Copyright (c) 2016, KlokanTech.com & OpenMapTiles contributors.
All rights reserved.

Code license: BSD 3-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Design license: CC-BY 4.0

See https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md for details on usage
*/
package com.onthegomap.flatmap.openmaptiles.layers;

import com.onthegomap.flatmap.FeatureCollector;
import com.onthegomap.flatmap.FeatureMerge;
import com.onthegomap.flatmap.VectorTile;
import com.onthegomap.flatmap.config.FlatmapConfig;
import com.onthegomap.flatmap.geo.GeometryException;
import com.onthegomap.flatmap.openmaptiles.MultiExpression;
import com.onthegomap.flatmap.openmaptiles.OpenMapTilesProfile;
import com.onthegomap.flatmap.openmaptiles.generated.OpenMapTilesSchema;
import com.onthegomap.flatmap.openmaptiles.generated.Tables;
import com.onthegomap.flatmap.reader.SourceFeature;
import com.onthegomap.flatmap.stats.Stats;
import com.onthegomap.flatmap.util.Translations;
import com.onthegomap.flatmap.util.ZoomFunction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class is ported to Java from https://github.com/openmaptiles/openmaptiles/tree/master/layers/landcover
 */
public class Landcover implements
  OpenMapTilesSchema.Landcover,
  OpenMapTilesProfile.NaturalEarthProcessor,
  Tables.OsmLandcoverPolygon.Handler,
  OpenMapTilesProfile.FeaturePostProcessor {

  public static final ZoomFunction<Number> MIN_PIXEL_SIZE_THRESHOLDS = ZoomFunction.fromMaxZoomThresholds(Map.of(
    13, 8,
    10, 4,
    9, 2
  ));
  private static final String NUM_POINTS_ATTR = "_numpoints";
  private static final Set<String> WOOD_OR_FOREST = Set.of(
    FieldValues.SUBCLASS_WOOD,
    FieldValues.SUBCLASS_FOREST
  );
  private final MultiExpression.MultiExpressionIndex<String> classMapping;

  public Landcover(Translations translations, FlatmapConfig config, Stats stats) {
    this.classMapping = FieldMappings.Class.index();
  }

  private String getClassFromSubclass(String subclass) {
    return subclass == null ? null : classMapping.getOrElse(Map.of(Fields.SUBCLASS, subclass), null);
  }

  @Override
  public void processNaturalEarth(String table, SourceFeature feature,
    FeatureCollector features) {
    record LandcoverInfo(String subclass, int minzoom, int maxzoom) {}
    LandcoverInfo info = switch (table) {
      case "ne_110m_glaciated_areas" -> new LandcoverInfo(FieldValues.SUBCLASS_GLACIER, 0, 1);
      case "ne_50m_glaciated_areas" -> new LandcoverInfo(FieldValues.SUBCLASS_GLACIER, 2, 4);
      case "ne_10m_glaciated_areas" -> new LandcoverInfo(FieldValues.SUBCLASS_GLACIER, 5, 6);
      case "ne_50m_antarctic_ice_shelves_polys" -> new LandcoverInfo("ice_shelf", 2, 4);
      case "ne_10m_antarctic_ice_shelves_polys" -> new LandcoverInfo("ice_shelf", 5, 6);
      default -> null;
    };
    if (info != null) {
      String clazz = getClassFromSubclass(info.subclass);
      if (clazz != null) {
        features.polygon(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
          .setAttr(Fields.CLASS, clazz)
          .setAttr(Fields.SUBCLASS, info.subclass)
          .setZoomRange(info.minzoom, info.maxzoom);
      }
    }
  }

  @Override
  public void process(Tables.OsmLandcoverPolygon element, FeatureCollector features) {
    String subclass = element.subclass();
    String clazz = getClassFromSubclass(subclass);
    if (clazz != null) {
      features.polygon(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
        .setMinPixelSizeOverrides(MIN_PIXEL_SIZE_THRESHOLDS)
        .setAttr(Fields.CLASS, clazz)
        .setAttr(Fields.SUBCLASS, subclass)
        .setNumPointsAttr(NUM_POINTS_ATTR)
        .setZoomRange(WOOD_OR_FOREST.contains(subclass) ? 9 : 7, 14);
    }
  }

  @Override
  public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items)
    throws GeometryException {
    if (zoom < 7 || zoom > 13) {
      for (var item : items) {
        item.attrs().remove(NUM_POINTS_ATTR);
      }
      return items;
    } else { // z7-13
      String groupKey = "_group";
      List<VectorTile.Feature> result = new ArrayList<>();
      List<VectorTile.Feature> toMerge = new ArrayList<>();
      for (var item : items) {
        Map<String, Object> attrs = item.attrs();
        Object numPointsObj = attrs.remove(NUM_POINTS_ATTR);
        Object subclassObj = attrs.get(Fields.SUBCLASS);
        if (numPointsObj instanceof Number num && subclassObj instanceof String subclass) {
          long numPoints = num.longValue();
          if (zoom >= 10) {
            if (WOOD_OR_FOREST.contains(subclass) && numPoints < 300) {
              attrs.put(groupKey, numPoints < 50 ? "<50" : "<300");
              toMerge.add(item);
            } else { // don't merge
              result.add(item);
            }
          } else if (zoom == 9) {
            if (WOOD_OR_FOREST.contains(subclass)) {
              attrs.put(groupKey, numPoints < 50 ? "<50" : numPoints < 300 ? "<300" : ">300");
              toMerge.add(item);
            } else { // don't merge
              result.add(item);
            }
          } else { // zoom between 7 and 8
            toMerge.add(item);
          }
        } else {
          result.add(item);
        }
      }
      var merged = FeatureMerge.mergeOverlappingPolygons(toMerge, 4);
      for (var item : merged) {
        item.attrs().remove(groupKey);
      }
      result.addAll(merged);
      return result;
    }
  }
}
