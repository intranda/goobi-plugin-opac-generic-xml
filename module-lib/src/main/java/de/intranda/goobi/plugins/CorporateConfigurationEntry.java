package de.intranda.goobi.plugins;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CorporateConfigurationEntry extends MetadataConfigurationEntry {

    private String mainNameXpath;
    private String subNameXpath;
    private String partNameXpath;

}
