package com.onthegomap.flatmap.openmaptiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onthegomap.flatmap.config.FlatmapConfig;
import com.onthegomap.flatmap.reader.osm.OsmElement;
import com.onthegomap.flatmap.stats.Stats;
import com.onthegomap.flatmap.util.Translations;
import com.onthegomap.flatmap.util.Wikidata;
import java.util.List;
import org.junit.jupiter.api.Test;

public class OpenMapTilesProfileTest {

  private final Wikidata.WikidataTranslations wikidataTranslations = new Wikidata.WikidataTranslations();
  private final Translations translations = Translations.defaultProvider(List.of("en", "es", "de"))
    .addTranslationProvider(wikidataTranslations);
  private final OpenMapTilesProfile profile = new OpenMapTilesProfile(translations, FlatmapConfig.defaults(),
    Stats.inMemory());

  @Test
  public void testCaresAboutWikidata() {
    var node = new OsmElement.Node(1, 1, 1);
    node.setTag("aeroway", "gate");
    assertTrue(profile.caresAboutWikidataTranslation(node));

    node.setTag("aeroway", "other");
    assertFalse(profile.caresAboutWikidataTranslation(node));
  }

  @Test
  public void testDoesntCareAboutWikidataForRoads() {
    var way = new OsmElement.Way(1);
    way.setTag("highway", "footway");
    assertFalse(profile.caresAboutWikidataTranslation(way));
  }
}
