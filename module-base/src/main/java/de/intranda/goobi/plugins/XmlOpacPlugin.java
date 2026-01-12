package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;

import org.apache.commons.lang3.StringUtils;
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
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;

import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.UghHelper;
import de.sub.goobi.helper.XmlTools;
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
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
public class XmlOpacPlugin implements IOpacPlugin {

    private static final long serialVersionUID = -2018204723594173535L;

    private XmlParser parser;

    protected String atstsl;

    @Getter
    private PluginType type = PluginType.Opac;

    @Getter
    private String title = "intranda_opac_xml";

    private int hit = 0;

    private String gattung;

    @Override
    public Fileformat search(String searchField, String searchValue, ConfigOpacCatalogue coc, Prefs prefs) throws Exception {
        //        if (namespaces == null) {
        loadConfiguration();
        //        }
        Fileformat mm = null;
        String response = null;
        if (StringUtils.isNotBlank(searchValue)) {
            String url = coc.getAddress();
            if (!url.contains("{pv.id}")) {
                Helper.setFehlerMeldung("address does not contain {pv.id} - sequence");
                hit = 0;
                return null;
            }
            url = url.replace("{pv.id}", searchValue);

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
                if ("NTLM".equalsIgnoreCase(parser.getAuthenticationType())) {
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(AuthScope.ANY, new NTCredentials(parser.getUsername(), parser.getPassword(), null, null));
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
                } else if ("BASIC".equalsIgnoreCase(parser.getAuthenticationType())) {
                    CredentialsProvider credsProvider = new BasicCredentialsProvider();
                    credsProvider.setCredentials(AuthScope.ANY,
                            new UsernamePasswordCredentials(parser.getUsername(), parser.getPassword()));
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

            // remove byte-order mark character
            if (response.startsWith("\uFEFF")) {
                response = response.substring(1);
            }

            // System.out.println(response);

            Element element = getRecordFromResponse(response);
            if (element == null) {
                hit = 0;
                return null;
            }
            hit = 1;

            // get namespace definition from root element, if nothing else is configured
            if (parser.getNamespaces() == null || parser.getNamespaces().isEmpty()) {
                parser.setNamespaces(element.getNamespacesInScope());
            }

            mm = new MetsMods(prefs);
            DigitalDocument digitalDocument = new DigitalDocument();
            mm.setDigitalDocument(digitalDocument);
            DocStruct volume = null;
            DocStruct anchor = null;
            // get doc type from xml record
            if (parser.getDocumentTypeQuery() != null) {
                List<String> val = parser.queryXmlFile(element, parser.getDocumentTypeQuery(), searchValue);
                if (val.isEmpty()) {
                    hit = 0;
                    log.info("No document type detected in xml file");
                    return null;
                }
                StringPair sp = parser.getDocstructMap().get(val.get(0));
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
                volume = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(parser.getDocumentType()));

                if (parser.getAnchorType() != null) {
                    anchor = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName(parser.getAnchorType()));
                    anchor.addChild(volume);
                    digitalDocument.setLogicalDocStruct(anchor);
                } else {
                    digitalDocument.setLogicalDocStruct(volume);
                }
            }

            gattung = volume.getType().getName();
            DocStruct physical = digitalDocument.createDocStruct(prefs.getDocStrctTypeByName("BoundBook"));
            digitalDocument.setPhysicalDocStruct(physical);
            for (MetadataConfigurationEntry entry : parser.getMetadataList()) {
                DocStruct ds = getConfiguredDocstruct(volume, anchor, physical, entry);
                parser.extractMetadata(element, entry, searchValue, ds, prefs);
            }

            for (PersonConfigurationEntry entry : parser.getPersonList()) {
                DocStruct ds = getConfiguredDocstruct(volume, anchor, physical, entry);
                parser.extractPerson(element, entry, searchValue, ds, prefs);
            }

            for (MetadataConfigurationEntry entry : parser.getCorporateList()) {
                DocStruct ds = getConfiguredDocstruct(volume, anchor, physical, entry);
                parser.extractMetadata(element, entry, searchValue, ds, prefs);
            }

            for (GroupConfigurationEntry entry : parser.getGroupList()) {
                DocStruct ds = getConfiguredDocstruct(volume, anchor, physical, entry);
                parser.extractGroup(element, entry, searchValue, ds, prefs);

            }

            // if main element has no identifier, use search value
            boolean identifierExists = false;
            for (Metadata md : volume.getAllMetadata()) {
                if ("CatalogIDDigital".equals(md.getType().getName())) {
                    identifierExists = true;
                    break;
                }
            }
            if (!identifierExists) {
                Metadata md = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
                md.setValue(searchValue);
                volume.addMetadata(md);
            }

        }
        return mm;
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

    private void loadConfiguration() {
        parser = new XmlParser();
        parser.loadConfiguration(title);
    }

    private Element getRecordFromResponse(String response) {
        SAXBuilder builder = XmlTools.getSAXBuilder();
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
