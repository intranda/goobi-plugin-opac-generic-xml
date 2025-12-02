package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
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
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.UghHelper;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import de.unigoettingen.sub.search.opac.ConfigOpacDoctype;
import io.goobi.workflow.api.connection.HttpUtils;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
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
@Log4j2
public class XmlOpacPlugin implements IOpacPlugin {

    private static final long serialVersionUID = -2018204723594173535L;

    private List<Namespace> namespaces = null;

    private transient List<ConfigurationEntry> metadataList = null;

    private transient ConfigurationEntry documentTypeQuery;
    private Map<String, StringPair> docstructMap;

    private String documentType = null;
    private String anchorType = null;
    protected String atstsl;

    private String authenticationType = "none";
    private String username;
    private String password;

    private transient XPathFactory xFactory = XPathFactory.instance();

    @Getter
    private PluginType type = PluginType.Opac;

    @Getter
    private String title = "intranda_opac_xml";

    private int hit = 0;

    private String gattung;

    @Override
    public Fileformat search(String inSuchfeld, String inSuchbegriff, ConfigOpacCatalogue coc, Prefs prefs) throws Exception {
        String myTitle = "";
        StringBuilder authors = new StringBuilder();
        if (namespaces == null) {
            loadConfiguration();
        }
        Fileformat mm = null;
        String response = null;
        if (StringUtils.isNotBlank(inSuchbegriff)) {
            String url = coc.getAddress();
            if (!url.contains("{pv.id}")) {
                Helper.setFehlerMeldung("address does not contain {pv.id} - sequence");
                hit = 0;
                return null;
            }
            url = url.replace("{pv.id}", inSuchbegriff);

            if (url.startsWith("file://")) {
                StorageProviderInterface spi = StorageProvider.getInstance();
                Path fileLocation = Paths.get(URI.create(url));
                if (spi.isFileExists(fileLocation)) {
                    try {
                        response = new String(Files.readAllBytes(fileLocation));
                    } catch (IOException ex) {
                        hit = 0;
                        log.error("Cannot open File: " + fileLocation.toString(), ex);
                        return null;
                    }
                }
            } else {
                CloseableHttpClient httpClient = null;
                if ("NTLM".equalsIgnoreCase(authenticationType)) {
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(AuthScope.ANY, new NTCredentials(username, password, null, null));
                    Registry<AuthSchemeProvider> authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider> create()
                            .register(AuthSchemes.NTLM, new NTLMSchemeFactory())
                            .build();

                    RequestConfig config = RequestConfig.custom()
                            .setConnectTimeout(60 * 1000)
                            .setConnectionRequestTimeout(60 * 1000)
                            .setCookieSpec(CookieSpecs.DEFAULT)
                            .setTargetPreferredAuthSchemes(Arrays.asList(AuthSchemes.NTLM, AuthSchemes.KERBEROS, AuthSchemes.SPNEGO))
                            .build();

                    httpClient = HttpClientBuilder.create()
                            .setDefaultCredentialsProvider(credsProvider)
                            .setDefaultAuthSchemeRegistry(authSchemeRegistry)
                            .setConnectionManager(new PoolingHttpClientConnectionManager())
                            .setDefaultCookieStore(new BasicCookieStore())
                            .setDefaultRequestConfig(config)
                            .build();
                } else if ("BASIC".equalsIgnoreCase(authenticationType)) {
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(AuthScope.ANY,
                            new UsernamePasswordCredentials(username, password));
                    httpClient = HttpClients.custom().setDefaultCredentialsProvider(credsProvider).build();
                } else {
                    // default client
                    httpClient = HttpClientBuilder.create().build();
                }

                HttpGet method = new HttpGet(url);

                response = httpClient.execute(method, HttpUtils.stringResponseHandler);
            }

            if (StringUtils.isBlank(response)) {
                hit = 0;
                return null;
            }
            Element element = getRecordFromResponse(response);
            if (element == null) {
                hit = 0;
                return null;
            }
            hit = 1;

            mm = new MetsMods(prefs);
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
                    anchor.addChild(volume);
                    digitalDocument.setLogicalDocStruct(anchor);
                } else {
                    digitalDocument.setLogicalDocStruct(volume);
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

            gattung = volume.getType().getName();
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
                                authors.append(value).append("; ");
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
                                if ("TitleDocMain".equals(sp.getMetadataName())) {
                                    myTitle = value.toLowerCase();
                                }
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
        }
        this.atstsl = createAtstsl(myTitle, authors.toString());
        return mm;
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
        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
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
            documentTypeQuery = new ConfigurationEntry();
            documentTypeQuery.setXpath(doctypequery.getString("@xpath"));
            documentTypeQuery.setXpathType(doctypequery.getString("@xpathType", "Element"));
        }

        fields = config.configurationsAt("mapping/element");
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

        authenticationType = config.getString("/authorization/@type", "none");
        username = config.getString("/authorization/username");
        password = config.getString("/authorization/password");
    }

    private Element getRecordFromResponse(String response) {
        SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
        builder.setFeature("http://xml.org/sax/features/validation", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            Document doc = builder.build(new StringReader(response), "utf-8");
            return doc.getRootElement();
        } catch (JDOMException | IOException e) {
            log.error(e);
        }
        return null;
    }

    @Override
    public int getHitcount() {
        return hit;
    }

    /* (non-Javadoc)
    * @see de.sub.goobi.Import.IOpac#getAtstsl()
    */
    @Override
    public String getAtstsl() {
        return this.atstsl;
    }

    @Override
    public ConfigOpacDoctype getOpacDocType() {
        return ConfigOpac.getInstance().getDoctypeByName(gattung);
    }

    @Override
    public String createAtstsl(String myTitle, String autor) {
        StringBuilder myAtsTsl = new StringBuilder();
        if (autor != null && !"".equals(autor)) {
            /* autor */
            if (autor.length() > 4) {
                myAtsTsl.append(autor.substring(0, 4));
            } else {
                myAtsTsl.append(autor);
                /* titel */
            }

            if (myTitle.length() > 4) {
                myAtsTsl.append(myTitle.substring(0, 4));
            } else {
                myAtsTsl.append(myTitle);
            }
        }

        if (autor == null || "".equals(autor)) {
            StringTokenizer tokenizer = new StringTokenizer(myTitle);
            int counter = 1;
            while (tokenizer.hasMoreTokens()) {
                String tok = tokenizer.nextToken();
                if (counter == 1) {
                    if (tok.length() > 4) {
                        myAtsTsl.append(tok.substring(0, 4));
                    } else {
                        myAtsTsl.append(tok);
                    }
                }
                if (counter == 2 || counter == 3) {
                    if (tok.length() > 2) {
                        myAtsTsl.append(tok.substring(0, 2));
                    } else {
                        myAtsTsl.append(tok);
                    }
                }
                if (counter == 4) {
                    if (tok.length() > 1) {
                        myAtsTsl.append(tok.substring(0, 1));
                    } else {
                        myAtsTsl.append(tok);
                    }
                }
                counter++;
            }
        }
        // replace umlauts
        String s = myAtsTsl.toString();
        s = UghHelper.convertUmlaut(s);
        s = s.replaceAll("[\\W]", "");
        return s;
    }

    @Override
    public void setAtstsl(String createAtstsl) {
        atstsl = createAtstsl;
    }

    @Override
    public String getGattung() {
        return gattung;
    }

}
