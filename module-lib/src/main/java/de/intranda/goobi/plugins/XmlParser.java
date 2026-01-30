package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang3.StringUtils;
import org.goobi.production.cli.helper.StringPair;
import org.jdom2.Attribute;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.xpath.XPathFactory;

import de.sub.goobi.config.ConfigPlugins;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import ugh.dl.Corporate;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.HoldingElement;
import ugh.dl.Metadata;
import ugh.dl.MetadataGroup;
import ugh.dl.MetadataGroupType;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.UGHException;

@Log4j2
@Getter
public class XmlParser {
    @Setter
    private List<Namespace> namespaces = null;

    private transient List<MetadataConfigurationEntry> metadataList = null;

    private transient List<PersonConfigurationEntry> personList = null;

    private transient List<CorporateConfigurationEntry> corporateList = null;
    private transient List<GroupConfigurationEntry> groupList = null;

    private transient MetadataConfigurationEntry documentTypeQuery;
    private Map<String, StringPair> docstructMap;

    private String documentType = null;
    private String anchorType = null;

    private String authenticationType = "none";
    private String username;
    private String password;

    private transient XPathFactory xFactory = XPathFactory.instance();

    // used for TMS node import
    private String splitRecordXPath;
    private String splitRecordXPathType;
    private String archiveIdentifierField;

    private String hierarchyXPath;
    private String hierarchyXPathType;
    private String hierarchySplitChar;

    private String processTemplateName;

    public void loadConfiguration(String configName) {

        docstructMap = new HashMap<>();
        namespaces = new ArrayList<>();
        metadataList = new ArrayList<>();
        personList = new ArrayList<>();
        corporateList = new ArrayList<>();
        groupList = new ArrayList<>();

        XMLConfiguration config = ConfigPlugins.getPluginConfig(configName);
        config.setExpressionEngine(new XPathExpressionEngine());
        List<HierarchicalConfiguration> fields = config.configurationsAt("/namespaces/namespace");
        for (HierarchicalConfiguration sub : fields) {
            Namespace namespace;
            String prefix = sub.getString("@prefix", null);
            if (StringUtils.isNotBlank(prefix)) {
                namespace = Namespace.getNamespace(prefix, sub.getString("@uri"));
            } else {
                namespace = Namespace.getNamespace(sub.getString("@uri"));
            }
            namespaces.add(namespace);
        }

        documentType = config.getString("/docstructs/documenttype[@isanchor='false']", null);
        anchorType = config.getString("/docstructs/documenttype[@isanchor='true']", null);

        List<HierarchicalConfiguration> docstructList = config.configurationsAt("/docstructs/docstruct");
        if (docstructList != null) {
            for (HierarchicalConfiguration docstruct : docstructList) {
                String xmlName = docstruct.getString("@xmlName");
                String rulesetName = docstruct.getString("@rulesetName");
                String anchorName = docstruct.getString("@anchorName", null);
                docstructMap.put(xmlName, new StringPair(rulesetName, anchorName));
            }
        }
        List<HierarchicalConfiguration> doctypequeries = config.configurationsAt("/docstructs/doctumenttypequery");
        if (!doctypequeries.isEmpty()) {
            HierarchicalConfiguration doctypequery = doctypequeries.get(0);
            documentTypeQuery = new MetadataConfigurationEntry();
            documentTypeQuery.setXpath(doctypequery.getString("@xpath"));
            documentTypeQuery.setXpathType(doctypequery.getString("@xpathType", "Element"));
        }

        fields = config.configurationsAt("mapping/metadata");
        for (HierarchicalConfiguration sub : fields) {
            MetadataConfigurationEntry entry = getMetadataConfiguration(sub);
            metadataList.add(entry);
        }

        fields = config.configurationsAt("mapping/person");
        for (HierarchicalConfiguration sub : fields) {
            PersonConfigurationEntry entry = getPersonConfiguration(sub);
            personList.add(entry);
        }

        fields = config.configurationsAt("mapping/corporate");
        for (HierarchicalConfiguration sub : fields) {
            CorporateConfigurationEntry entry = getCorporateConfiguration(sub);
            corporateList.add(entry);
        }

        fields = config.configurationsAt("mapping/group");
        for (HierarchicalConfiguration sub : fields) {
            GroupConfigurationEntry entry = new GroupConfigurationEntry();
            entry.setMetadataName(sub.getString("@metadata"));
            entry.setXpath(sub.getString("@xpath"));
            entry.setLevel(sub.getString("@level", "topstruct"));
            entry.setAuthorityDataXpath(sub.getString("@authorityData"));
            entry.setArchiveFieldLevel(sub.getString("@archiveLevel"));
            entry.setArchiveFieldName(sub.getString("@archiveField"));
            for (HierarchicalConfiguration hc : sub.configurationsAt("metadata")) {
                MetadataConfigurationEntry mce = getMetadataConfiguration(hc);
                entry.getMetadataList().add(mce);
            }

            for (HierarchicalConfiguration hc : sub.configurationsAt("person")) {
                PersonConfigurationEntry pce = getPersonConfiguration(hc);
                entry.getPersonList().add(pce);
            }
            for (HierarchicalConfiguration hc : sub.configurationsAt("corporate")) {
                CorporateConfigurationEntry cce = getCorporateConfiguration(hc);
                entry.getCorporateList().add(cce);
            }
            groupList.add(entry);
        }

        authenticationType = config.getString("/authorization/@type", "none");
        username = config.getString("/authorization/username");
        password = config.getString("/authorization/password");

        splitRecordXPath = config.getString("/archiveImport/splitRecordXPath/@xpath");
        splitRecordXPathType = config.getString("/archiveImport/splitRecordXPath/@xpathType", "Element");
        archiveIdentifierField = config.getString("/archiveImport/identifierField");

        hierarchyXPath = config.getString("/archiveImport/hierarchyXPath/@xpath");
        hierarchyXPathType = config.getString("/archiveImport/hierarchyXPath/@xpathType", "Element");
        hierarchySplitChar = config.getString("/archiveImport/hierarchyXPath/@split", "_");
        processTemplateName = config.getString("/archiveImport/processTemplateName", "");
    }

    public DocStruct getConfiguredDocstruct(DocStruct volume, DocStruct anchor, DocStruct physical, MetadataConfigurationEntry sp) {
        DocStruct ds = null;
        switch (sp.getLevel().toLowerCase()) {
            case "physical":
                ds = physical;
                break;
            case "topstruct":
                ds = volume;
                break;
            case "anchor":
                ds = anchor;
        }
        return ds;
    }

    public void extractGroup(Element element, GroupConfigurationEntry entry, String searchValue, DocStruct ds, Prefs prefs) {
        if (StringUtils.isBlank(entry.getMetadataName())) {
            // abort if no metadata field is configured
            return;
        }
        MetadataGroupType type = prefs.getMetadataGroupTypeByName(entry.getMetadataName());
        if (type == null) {
            log.error("Cannot initialize metadata type " + entry.getMetadataName());
            return;
        } else {
            String xpath = entry.getXpath().replace("{pv.id}", searchValue);
            List<Element> groups = xFactory.compile(xpath, Filters.element(), null, namespaces).evaluate(element);
            for (Element groupElement : groups) {
                try {
                    MetadataGroup grp = new MetadataGroup(type);
                    for (MetadataConfigurationEntry mce : entry.getMetadataList()) {
                        extractMetadata(groupElement, mce, searchValue, grp, prefs);
                    }

                    for (PersonConfigurationEntry pce : entry.getPersonList()) {
                        extractPerson(groupElement, pce, searchValue, grp, prefs);
                    }

                    for (CorporateConfigurationEntry cce : entry.getCorporateList()) {
                        extractMetadata(groupElement, cce, searchValue, grp, prefs);
                    }

                    if (!grp.getMetadataList().isEmpty() || !grp.getCorporateList().isEmpty() || !grp.getPersonList().isEmpty()) {
                        ds.addMetadataGroup(grp);
                    }

                } catch (MetadataTypeNotAllowedException e) {
                    log.error(e);
                }

            }
        }
    }

    public void extractPerson(Element element, PersonConfigurationEntry entry, String id, HoldingElement ds, Prefs prefs) {
        if (StringUtils.isBlank(entry.getMetadataName())) {
            // abort if no metadata field is configured
            return;
        }
        MetadataType mdt = prefs.getMetadataTypeByName(entry.getMetadataName());
        if (mdt == null) {
            log.error("Cannot initialize metadata type " + entry.getMetadataName());
            return;
        } else {
            String xpath = entry.getXpath().replace("{pv.id}", id);
            List<Element> persons = xFactory.compile(xpath, Filters.element(), null, namespaces).evaluate(element);
            for (Element personElement : persons) {
                String firstname = null;
                String lastname = null;
                String authorityUri = null;

                if (StringUtils.isNotBlank(entry.getFirstnameXpath())) {
                    Element field = xFactory.compile(entry.getFirstnameXpath(), Filters.element(), null, namespaces).evaluateFirst(personElement);
                    firstname = field != null ? field.getValue() : "";
                }
                if (StringUtils.isNotBlank(entry.getLastnameXpath())) {
                    Element field = xFactory.compile(entry.getLastnameXpath(), Filters.element(), null, namespaces).evaluateFirst(personElement);
                    lastname = field != null ? field.getValue() : "";
                }
                if (StringUtils.isNotBlank(entry.getAuthorityDataXpath())) {
                    Element field = xFactory.compile(entry.getAuthorityDataXpath(), Filters.element(), null, namespaces).evaluateFirst(personElement);
                    authorityUri = field != null ? field.getValue() : "";
                }
                if (StringUtils.isNotBlank(firstname) || StringUtils.isNotBlank(lastname)) {
                    try {
                        Person p = new Person(mdt);
                        p.setLastname(lastname);
                        p.setFirstname(firstname);
                        if (StringUtils.isNotBlank(authorityUri)) {
                            p.setAuthorityFile("gnd", "", authorityUri);
                        }
                        ds.addPerson(p);
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }
            }
        }
    }

    public void extractMetadata(Element element, MetadataConfigurationEntry entry, String id, HoldingElement ds, Prefs prefs) {
        if (StringUtils.isBlank(entry.getMetadataName())) {
            // abort if no metadata field is configured
            return;
        }
        MetadataType mdt = prefs.getMetadataTypeByName(entry.getMetadataName());
        if (mdt == null) {
            log.error("Cannot initialize metadata type " + entry.getMetadataName());
            return;
        } else {
            String xpath = entry.getXpath().replace("{pv.id}", id);
            if ("Element".equalsIgnoreCase(entry.getXpathType())) {
                List<Element> data = xFactory.compile(xpath, Filters.element(), null, namespaces).evaluate(element);
                for (Element e : data) {
                    String value = e.getValue();
                    String authorityUri = "";
                    if (StringUtils.isNotBlank(entry.getAuthorityDataXpath())) {
                        authorityUri = xFactory.compile(entry.getAuthorityDataXpath(), Filters.fstring(), null, namespaces).evaluateFirst(e);
                    }
                    createMetadata(value, mdt, ds, authorityUri);

                }
            } else if ("Attribute".equalsIgnoreCase(entry.getXpathType())) {
                List<Attribute> data = xFactory.compile(xpath, Filters.attribute(), null, namespaces).evaluate(element);
                for (Attribute a : data) {
                    String value = a.getValue();
                    createMetadata(value, mdt, ds, null);
                }
            } else {
                List<String> data = xFactory.compile(xpath, Filters.fstring(), null, namespaces).evaluate(element);
                for (String value : data) {
                    createMetadata(value, mdt, ds, null);
                }
            }
        }
    }

    public void createMetadata(String value, MetadataType mdt, HoldingElement ds, String authorityUri) {
        try {
            if (mdt.getIsPerson()) {
                Person p = new Person(mdt);
                if (value.contains(",")) {
                    p.setLastname(value.substring(0, value.indexOf(",")).trim());
                    p.setFirstname(value.substring(value.indexOf(",") + 1).trim());
                } else {
                    p.setLastname(value);
                }
                if (StringUtils.isNotBlank(authorityUri)) {
                    p.setAuthorityFile("gnd", "", authorityUri);
                }

                ds.addPerson(p);

            } else if (mdt.isCorporate()) {
                Corporate c = new Corporate(mdt);
                c.setMainName(value);
                if (StringUtils.isNotBlank(authorityUri)) {
                    c.setAuthorityFile("gnd", "", authorityUri);
                }
                ds.addCorporate(c);
            } else {

                Metadata md = new Metadata(mdt);
                md.setValue(value);
                if (StringUtils.isNotBlank(authorityUri)) {
                    md.setAuthorityFile("gnd", "", authorityUri);
                }
                ds.addMetadata(md);
            }
        } catch (

        Exception e) {
            log.error(e);
        }
    }

    public List<String> queryXmlFile(Element element, MetadataConfigurationEntry entry, String id) {
        List<String> metadataValues = new ArrayList<>();

        String xpath = entry.getXpath().replace("{pv.id}", id);
        if ("Element".equalsIgnoreCase(entry.getXpathType())) {
            List<Element> data = xFactory.compile(xpath, Filters.element(), null, namespaces).evaluate(element);
            for (Element e : data) {
                String value = e.getValue();
                metadataValues.add(value);
            }
        } else if ("Attribute".equalsIgnoreCase(entry.getXpathType())) {
            List<Attribute> data = xFactory.compile(xpath, Filters.attribute(), null, namespaces).evaluate(element);
            for (Attribute a : data) {
                String value = a.getValue();
                metadataValues.add(value);
            }
        } else {
            List<String> data = xFactory.compile(xpath, Filters.fstring(), null, namespaces).evaluate(element);
            for (String value : data) {
                metadataValues.add(value);
            }
        }
        return metadataValues;
    }

    public CorporateConfigurationEntry getCorporateConfiguration(HierarchicalConfiguration sub) {
        CorporateConfigurationEntry entry = new CorporateConfigurationEntry();
        entry.setMetadataName(sub.getString("@metadata"));
        entry.setXpath(sub.getString("@xpath"));
        entry.setLevel(sub.getString("@level", "topstruct"));
        entry.setAuthorityDataXpath(sub.getString("@authorityData"));
        entry.setAuthorityDataXpath(sub.getString("@authorityData"));
        entry.setArchiveFieldLevel(sub.getString("@archiveLevel"));
        entry.setArchiveFieldName(sub.getString("@archiveField"));
        return entry;
    }

    public PersonConfigurationEntry getPersonConfiguration(HierarchicalConfiguration sub) {
        PersonConfigurationEntry entry = new PersonConfigurationEntry();
        entry.setMetadataName(sub.getString("@metadata"));
        entry.setXpath(sub.getString("@xpath"));
        entry.setLevel(sub.getString("@level", "topstruct"));
        entry.setAuthorityDataXpath(sub.getString("@authorityData"));
        entry.setFirstnameXpath(sub.getString("@firstname"));
        entry.setLastnameXpath(sub.getString("@lastname"));
        entry.setAuthorityDataXpath(sub.getString("@authorityData"));
        entry.setArchiveFieldLevel(sub.getString("@archiveLevel"));
        entry.setArchiveFieldName(sub.getString("@archiveField"));
        return entry;
    }

    public MetadataConfigurationEntry getMetadataConfiguration(HierarchicalConfiguration sub) {
        String metadataName = sub.getString("@metadata");
        String xpathValue = sub.getString("@xpath");
        String level = sub.getString("@level", "topstruct");
        String xpathType = sub.getString("@xpathType", "Element");
        MetadataConfigurationEntry entry = new MetadataConfigurationEntry();
        entry.setLevel(level);
        entry.setMetadataName(metadataName);
        entry.setXpath(xpathValue);
        entry.setXpathType(xpathType);
        entry.setAuthorityDataXpath(sub.getString("@authorityData"));
        entry.setArchiveFieldLevel(sub.getString("@archiveLevel"));
        entry.setArchiveFieldName(sub.getString("@archiveField"));
        return entry;
    }

    public DigitalDocument createDocument(Prefs prefs, Element element, String searchValue) throws UGHException {
        DigitalDocument digitalDocument = new DigitalDocument();
        DocStruct volume = null;
        DocStruct anchor = null;
        if (getDocumentTypeQuery() != null) {
            List<String> val = queryXmlFile(element, getDocumentTypeQuery(), searchValue);
            if (val.isEmpty()) {

                return null;
            }
            StringPair sp = getDocstructMap().get(val.get(0));
            if (sp == null) {
                log.info("Unknown type found: " + val.get(0));
                return null;
            }
            volume = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(sp.getOne()));
            if (sp.getTwo() != null) {
                anchor = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(sp.getTwo()));
                anchor.addChild(volume);
                digitalDocument.setLogicalDocStruct(anchor);
            } else {
                digitalDocument.setLogicalDocStruct(volume);
            }
            // use configured doc type
        } else {
            volume = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(getDocumentType()));

            if (getAnchorType() != null) {
                anchor = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(getAnchorType()));
                anchor.addChild(volume);
                digitalDocument.setLogicalDocStruct(anchor);
            } else {
                digitalDocument.setLogicalDocStruct(volume);
            }
        }

        return digitalDocument;
    }

}
