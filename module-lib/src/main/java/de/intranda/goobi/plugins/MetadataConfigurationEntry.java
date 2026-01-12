package de.intranda.goobi.plugins;

import lombok.Data;

@Data
public class MetadataConfigurationEntry {

    private String metadataName;

    private String level; // topstruct, anchor, physical, empty

    private String xpath;

    private String xpathType = "Element"; // Element, Attribute, String

    private String authorityDataXpath;

}
