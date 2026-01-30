package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GroupConfigurationEntry extends MetadataConfigurationEntry {

    private transient List<MetadataConfigurationEntry> metadataList = new ArrayList<>();

    private transient List<PersonConfigurationEntry> personList = new ArrayList<>();

    private transient List<CorporateConfigurationEntry> corporateList = new ArrayList<>();

}
