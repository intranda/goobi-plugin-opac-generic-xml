package de.intranda.goobi.plugins;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PersonConfigurationEntry extends MetadataConfigurationEntry {

    private String firstnameXpath;
    private String lastnameXpath;

}
