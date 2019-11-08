package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.production.cli.helper.StringPair;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.jdom2.xpath.XPathFactory;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.HttpClientHelper;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import de.unigoettingen.sub.search.opac.ConfigOpacDoctype;
import lombok.Getter;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j
public class XmlOpacPlugin implements IOpacPlugin {

    private List<Namespace> namespaces = null;

    private List<ConfigurationEntry> metadataList = null;

    private ConfigurationEntry documentTypeQuery;
    private Map<String, StringPair> docstructMap;

    private String documentType = null;
    private String anchorType = null;

    private XPathFactory xFactory = XPathFactory.instance();

    @Getter
    private PluginType type = PluginType.Opac;

    @Getter
    private String title = "intranda_opac_xml";

    private int hit = 0;

    @Override
    public Fileformat search(String inSuchfeld, String inSuchbegriff, ConfigOpacCatalogue coc, Prefs prefs) throws Exception {

        if (namespaces == null) {
            loadConfiguration();
        }
        if (StringUtils.isNotBlank(inSuchbegriff)) {
            String url = coc.getAddress() + inSuchbegriff;
            // TODO
            String response = HttpClientHelper.getStringFromUrl(url);

            Element element = getRecordFromResponse(response);
            if (element == null) {
                hit = 0;
                return null;
            }
            hit = 1;

            Fileformat mm = new MetsMods(prefs);
            DigitalDocument digitalDocument = new DigitalDocument();
            mm.setDigitalDocument(digitalDocument);
            DocStruct volume = null;
            DocStruct anchor = null;
            // get doc type from xml record
            if (documentTypeQuery != null) {
                List<String> val = queryXmlFile(element, documentTypeQuery);
                if (val.isEmpty()) {
                    hit = 0;
                    log.info("No document type detected in xml file");
                    return null;
                }
                StringPair sp = docstructMap.get(val.get(0));
                if (sp == null) {
                    log.info("Unknown type found: " + val.get(0));
                    return null;
                }
                volume = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(sp.getOne()));
                if (sp.getTwo() != null) {
                    anchor = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(sp.getTwo()));
                }
                // use configured doc type
            } else {
                volume = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(documentType));

                if (anchorType != null) {
                    anchor = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(anchorType));
                    anchor.addChild(volume);
                    digitalDocument.setLogicalDocStruct(anchor);
                } else {
                    digitalDocument.setLogicalDocStruct(volume);
                }

            }

            DocStruct physical = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            digitalDocument.setPhysicalDocStruct(physical);

            for (ConfigurationEntry sp : metadataList) {
                List<String> metadataValues = queryXmlFile(element, sp);

                MetadataType mdt = prefs.getMetadataTypeByName(sp.getMetadataName());
                if (mdt == null) {
                    log.error("Cannot initialize metadata type " + sp.getMetadataName());
                } else {

                    for (String value : metadataValues) {
                        try {
                            if (mdt.getIsPerson()) {
                                Person p = new Person(mdt);
                                if (value.contains(",")) {
                                    p.setLastname(value.substring(0, value.indexOf(",")).trim());
                                    p.setFirstname(value.substring(value.indexOf(",") + 1).trim());
                                } else {
                                    p.setLastname(value);
                                }
                                if ("physical".equals(sp.getLevel())) {
                                    // add it to phys
                                    physical.addPerson(p);
                                } else if ("topstruct".equals(sp.getLevel())) {
                                    // add it to topstruct
                                    volume.addPerson(p);
                                } else if ("anchor".equals(sp.getLevel()) && anchor != null) {
                                    // add it to anchor
                                    anchor.addPerson(p);
                                }
                            } else {

                                Metadata md = new Metadata(mdt);
                                md.setValue(value);
                                if ("physical".equals(sp.getLevel())) {
                                    // add it to phys
                                    physical.addMetadata(md);
                                } else if ("topstruct".equals(sp.getLevel())) {
                                    // add it to topstruct
                                    volume.addMetadata(md);
                                } else if ("anchor".equals(sp.getLevel()) && anchor != null) {
                                    // add it to anchor
                                    anchor.addMetadata(md);
                                }
                            }
                        } catch (Exception e) {
                            log.error(e);
                        }

                    }
                }
            }
            return mm;

        }
        return null;
    }

    private List<String> queryXmlFile(Element element, ConfigurationEntry entry) {
        List<String> metadataValues = new ArrayList<>();
        if ("Element".equalsIgnoreCase(entry.getXpathType())) {
            List<Element> data = xFactory.compile(entry.getXpath(), Filters.element(), null, namespaces).evaluate(element);
            for (Element e : data) {
                String value = e.getValue();
                metadataValues.add(value);
            }
        } else if ("Attribute".equalsIgnoreCase(entry.getXpathType())) {
            List<Attribute> data = xFactory.compile(entry.getXpath(), Filters.attribute(), null, namespaces).evaluate(element);
            for (Attribute a : data) {
                String value = a.getValue();
                metadataValues.add(value);
            }

        } else {
            List<String> data = xFactory.compile(entry.getXpath(), Filters.fstring(), null, namespaces).evaluate(element);
            for (String value : data) {
                metadataValues.add(value);
            }
        }
        return metadataValues;
    }

    private void loadConfiguration() {

        docstructMap = new HashMap<>();
        namespaces = new ArrayList<>();
        metadataList = new ArrayList<>();
        XMLConfiguration config = ConfigPlugins.getPluginConfig("opac_ead");
        config.setExpressionEngine(new XPathExpressionEngine());
        List<HierarchicalConfiguration> fields = config.configurationsAt("/namespaces/namespace");
        for (HierarchicalConfiguration sub : fields) {
            Namespace namespace = Namespace.getNamespace(sub.getString("@prefix"), sub.getString("@uri"));
            namespaces.add(namespace);
        }

        documentType = config.getString("/documenttype[@isanchor='false']", null);
        anchorType = config.getString("/documenttype[@isanchor='true']", null);

        List<HierarchicalConfiguration> docstructList = config.configurationsAt("/docstructs/docstruct");
        if (docstructList != null) {
            for (HierarchicalConfiguration docstruct : docstructList) {
                String xmlName = docstruct.getString("@xmlName");
                String rulesetName = docstruct.getString("@rulesetName");
                String anchorName = docstruct.getString("@anchorName", null);
                docstructMap.put(xmlName, new StringPair(rulesetName, anchorName));
            }
        }
        HierarchicalConfiguration doctypequery = config.configurationAt("/docstructs/doctumenttypequery");
        if (doctypequery != null) {
            documentTypeQuery = new ConfigurationEntry();
            documentTypeQuery.setXpath(doctypequery.getString("@xpath"));
            documentTypeQuery.setXpathType(doctypequery.getString("@xpathType", "Element"));
        }

        fields = config.configurationsAt("mapping/metadata");
        for (HierarchicalConfiguration sub : fields) {
            String metadataName = sub.getString("@name");
            String xpathValue = sub.getString("@xpath");
            String level = sub.getString("@level", "topstruct");
            String xpathType = sub.getString("@xpathType", "Element");
            ConfigurationEntry entry = new ConfigurationEntry();
            entry.setLevel(level);
            entry.setMetadataName(metadataName);
            entry.setXpath(xpathValue);
            entry.setXpathType(xpathType);
            metadataList.add(entry);
        }
    }

    private Element getRecordFromResponse(String response) {
        SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
        builder.setFeature("http://xml.org/sax/features/validation", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            Document doc = builder.build(new StringReader(response), "utf-8");
            Element oaiRootElement = doc.getRootElement();

            return oaiRootElement;
        } catch (JDOMException | IOException e) {
            log.error(e);
        }
        return null;
    }

    @Override
    public int getHitcount() {
        return hit;
    }

    @Override
    public String getAtstsl() {
        return null;
    }

    @Override
    public ConfigOpacDoctype getOpacDocType() {
        return null;
    }

    @Override
    public String createAtstsl(String value, String value2) {
        return null;
    }

    @Override
    public void setAtstsl(String createAtstsl) {

    }

    @Override
    public String getGattung() {
        return null;
    }

}
