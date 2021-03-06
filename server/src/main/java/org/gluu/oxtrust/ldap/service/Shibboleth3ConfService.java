/*
 * oxTrust is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2016, Gluu
 */
package org.gluu.oxtrust.ldap.service;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.inject.Named;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.util.ClassUtils;
import org.gluu.config.oxtrust.AppConfiguration;
import org.gluu.config.oxtrust.AttributeResolverConfiguration;
import org.gluu.config.oxtrust.LdapOxTrustConfiguration;
import org.gluu.config.oxtrust.NameIdConfig;
import org.gluu.config.oxtrust.ShibbolethCASProtocolConfiguration;
import org.gluu.model.GluuAttribute;
import org.gluu.model.GluuStatus;
import org.gluu.model.GluuUserRole;
import org.gluu.model.SchemaEntry;
import org.gluu.oxtrust.config.ConfigurationFactory;
import org.gluu.oxtrust.model.GluuConfiguration;
import org.gluu.oxtrust.model.GluuCustomAttribute;
import org.gluu.oxtrust.model.GluuMetadataSourceType;
import org.gluu.oxtrust.model.GluuSAMLFederationProposal;
import org.gluu.oxtrust.model.GluuSAMLTrustRelationship;
import org.gluu.oxtrust.model.SamlAcr;
import org.gluu.oxtrust.util.EasyCASSLProtocolSocketFactory;
import org.gluu.persist.PersistenceEntryManager;
import org.gluu.saml.metadata.SAMLMetadataParser;
import org.gluu.service.SchemaService;
import org.gluu.service.XmlService;
import org.gluu.util.INumGenerator;
import org.gluu.util.OxConstants;
import org.gluu.util.StringHelper;
import org.gluu.util.Util;
import org.gluu.util.exception.InvalidConfigurationException;
import org.gluu.util.io.FileUploadWrapper;
import org.gluu.util.io.HTTPFileDownloader;
import org.gluu.util.security.StringEncrypter.EncryptionException;
import org.gluu.xml.GluuErrorHandler;
import org.gluu.xml.XMLValidator;
import org.opensaml.xml.schema.SchemaBuilder;
import org.opensaml.xml.schema.SchemaBuilder.SchemaLanguage;
import org.slf4j.Logger;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;

/**
 * Provides operations with attributes
 * 
 * @author Dmitry Ognyannikov, 2016
 */
@Stateless
@Named("shibboleth3ConfService")
public class Shibboleth3ConfService implements Serializable {

	private List<String> schemaValidationFileNames = new ArrayList<>();
	private static final long serialVersionUID = 6752452480800274694L;
	private static final String SHIB3_IDP_CONF_FOLDER = "conf";
	private static final String SHIB3_IDP_AUNTHN_FOLDER = "authn";
	public static final String SHIB3_IDP_METADATA_FOLDER = "metadata";
	private static final String SHIB3_IDP_METADATA_PROVIDERS_FILE = "metadata-providers.xml";
	private static final String SHIB3_IDP_ATTRIBUTE_FILTER_FILE = "attribute-filter.xml";
	private static final String SHIB3_IDP_ATTRIBUTE_RESOLVER_FILE = "attribute-resolver.xml";
	private static final String SHIB3_IDP_RELYING_PARTY_FILE = "relying-party.xml";
	private static final String SHIB3_IDP_CAS_PROTOCOL_FILE = "cas-protocol.xml";
	public static final String SHIB3_IDP_IDP_METADATA_FILE = "idp-metadata.xml";
	public static final String SHIB3_IDP_SP_METADATA_FILE = "sp-metadata.xml";
	public static final String SHIB3_SP_ATTRIBUTE_MAP_FILE = "attribute-map.xml";
	public static final String SHIB3_SP_SHIBBOLETH2_FILE = "shibboleth2.xml";
	private static final String SHIB3_SP_READ_ME = "/WEB-INF/resources/doc/README_SP.pdf";
	private static final String SHIB3_SP_READ_ME_WINDOWS = "/WEB-INF/resources/doc/README_SP_windows.pdf";
	private static final String SHIB3_SAML_NAMEID_FILE = "saml-nameid.xml";
	private static final String SHIB3_SAML_NAMEID_PROPS_FILE = "saml-nameid.properties";

	private static final String SHIB3_SP_METADATA_FILE_PATTERN = "%s-sp-metadata.xml";
	public static final String PUBLIC_CERTIFICATE_START_LINE = "-----BEGIN CERTIFICATE-----";
	public static final String PUBLIC_CERTIFICATE_END_LINE = "-----END CERTIFICATE-----";
	public static final String SHIB3_IDP_PROPERTIES_FILE = "idp.properties";
	private static final String SHIB3_IDP_LOGIN_CONFIG_FILE = "login.config";

	private static final String SHIB3_METADATA_FILE_PATTERN = "%s-metadata.xml";

	public static final String SHIB3_IDP_TEMPMETADATA_FOLDER = "temp_metadata";

	public static final String SHIB3_IDP_SP_KEY_FILE = "spkey.key";

	public static final String SHIB3_IDP_SP_CERT_FILE = "spcert.crt";

	public static final String GLUU_SAML_OXAUTH_SUPPORTED_PRINCIPALS_FILE = "oxauth-supported-principals.xml";

	@Inject
	private AttributeService attributeService;

	@Inject
	private TemplateService templateService;

	@Inject
	private Logger log;

	@Inject
	private FilterService filterService;

	@Inject
	private ConfigurationService configurationService;

	@Inject
	private ConfigurationFactory configurationFactory;

	@Inject
	private AppConfiguration appConfiguration;

	@Inject
	private EncryptionService encryptionService;

	@Inject
	private XmlService xmlService;

	@Inject
	private ProfileConfigurationService profileConfigurationService;

	@Inject
	@Named("casService")
	private CASService casService;

	@Inject
	private SchemaService shemaService;

	@Inject
	private TrustService trustService;

	@Inject
	private PersistenceEntryManager persistenceEntryManager;

	@Inject
	private PersonService personService;

	public boolean generateConfigurationFiles(SamlAcr[] acrs) {
		log.info(">>>>>>>>>> IN generateConfigurationFiles(SamlAcr[] acrs)...");
		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			throw new InvalidConfigurationException("Failed to update configuration due to undefined IDP root folder");
		}

		String idpConfAuthnFolder = getIdpConfAuthnDir();
		List<String> acrs2 = new ArrayList<String>();
		for (SamlAcr acr : acrs)
			acrs2.add(acr.getClassRef());

		VelocityContext context = new VelocityContext();
		context.put("acrs", acrs2);

		// Generate metadata-providers.xml
		String oxAuthSupportedPrincipals = templateService.generateConfFile(GLUU_SAML_OXAUTH_SUPPORTED_PRINCIPALS_FILE,
				context);
		boolean result = templateService.writeConfFile(idpConfAuthnFolder + GLUU_SAML_OXAUTH_SUPPORTED_PRINCIPALS_FILE,
				oxAuthSupportedPrincipals);

		log.info(">>>>>>>>>> LEAVING generateConfigurationFiles(SamlAcr[] acrs)...");
		return result;
	}

	/*
	 * Generate relying-party.xml, attribute-filter.xml, attribute-resolver.xml
	 */
	public boolean generateConfigurationFiles(List<GluuSAMLTrustRelationship> trustRelationships) {

		log.info(">>>>>>>>>> IN Shibboleth3ConfService.generateConfigurationFiles()...");

		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			throw new InvalidConfigurationException("Failed to update configuration due to undefined IDP root folder");
		}

		String idpConfFolder = getIdpConfDir();
		String idpMetadataFolder = getIdpMetadataDir();

		// Prepare data for files
		initAttributes(trustRelationships);
		HashMap<String, Object> trustParams = initTrustParamMap(trustRelationships);
		HashMap<String, Object> attrParams = initAttributeParamMap(trustRelationships);
		HashMap<String, Object> casParams = initCASParamMap();
		HashMap<String, Object> attrResolverParams = initAttributeResolverParamMap();

		boolean result = (trustParams != null) && (attrParams != null) && (casParams != null)
				&& (attrResolverParams != null);
		if (!result) {
			log.error(
					">>>>>>>>>> Shibboleth3ConfService.generateConfigurationFiles() - params preparation failed, break files generation");
			return result;
		}

		VelocityContext context = prepareVelocityContext(trustParams, attrParams, casParams, attrResolverParams,
				idpMetadataFolder);

		// Generate metadata-providers.xml
		String metadataProviders = templateService.generateConfFile(SHIB3_IDP_METADATA_PROVIDERS_FILE, context);
		// Generate attribute-resolver.xml
		String attributeResolver = templateService.generateConfFile(SHIB3_IDP_ATTRIBUTE_RESOLVER_FILE, context);

		// Generate attribute-filter.xml
		String attributeFilter = templateService.generateConfFile(SHIB3_IDP_ATTRIBUTE_FILTER_FILE, context);
		// Generate relying-party.xml
		String relyingParty = templateService.generateConfFile(SHIB3_IDP_RELYING_PARTY_FILE, context);
		// Generate cas-protocol.xml
		String casProtocol = templateService.generateConfFile(SHIB3_IDP_CAS_PROTOCOL_FILE, context);
		// Generate shibboleth2.xml
		String shibConfig = templateService.generateConfFile(SHIB3_SP_SHIBBOLETH2_FILE, context);
		// Generate saml-nameid.xml
		String samlnamedConfig = templateService.generateConfFile(SHIB3_SAML_NAMEID_FILE, context);
		// Generate saml-nameid.properties
		String samlnamedPropsConfig = templateService.generateConfFile(SHIB3_SAML_NAMEID_PROPS_FILE, context);
		// Generate handler.xml
		// String profileHandler =
		// templateService.generateConfFile(SHIB3_IDP_PROFILE_HADLER, context);

		// Generate attribute-map.xml
		// String attributeMap =
		// templateService.generateConfFile(SHIB2_SP_ATTRIBUTE_MAP, context);

		// result = (metadataProviders != null) && (attributeFilter != null) &&
		// (attributeResolver != null) && (relyingParty != null) && (shibConfig != null)
		// && (profileHandler != null);
		result = (metadataProviders != null) && (attributeFilter != null) && (attributeResolver != null)
				&& (relyingParty != null) && (casProtocol != null) && (shibConfig != null);

		// Write metadata-providers.xml
		result &= templateService.writeConfFile(idpConfFolder + SHIB3_IDP_METADATA_PROVIDERS_FILE, metadataProviders);
		// Write attribute-resolver.xml
		result &= templateService.writeConfFile(idpConfFolder + SHIB3_IDP_ATTRIBUTE_RESOLVER_FILE, attributeResolver);
		// Write attribute-filter.xml
		result &= templateService.writeConfFile(idpConfFolder + SHIB3_IDP_ATTRIBUTE_FILTER_FILE, attributeFilter);
		// Write relying-party.xml
		result &= templateService.writeConfFile(idpConfFolder + SHIB3_IDP_RELYING_PARTY_FILE, relyingParty);
		// Write cas-protocol.xml
		result &= templateService.writeConfFile(idpConfFolder + SHIB3_IDP_CAS_PROTOCOL_FILE, casProtocol);
		// Write shibboleth2.xml
		result &= templateService.writeConfFile(getSpShibboleth3FilePath(), shibConfig);
		// Write saml-nameid.xml
		result &= templateService.writeConfFile(idpConfFolder + SHIB3_SAML_NAMEID_FILE, samlnamedConfig);
		// Write saml-nameid.properties
		result &= templateService.writeConfFile(idpConfFolder + SHIB3_SAML_NAMEID_PROPS_FILE, samlnamedPropsConfig);

		// Write handler.xml
		// result &= templateService.writeConfFile(idpConfFolder +
		// SHIB3_IDP_PROFILE_HADLER, profileHandler);

		// Write attribute-map.xml
		// result &= templateService.writeConfFile(spConfFolder +
		// SHIB2_SP_ATTRIBUTE_MAP, attributeMap);

		log.info(">>>>>>>>>> LEAVING Shibboleth3ConfService.generateConfigurationFiles()...");

		return result;
	}

	/*
	 * Init attributes
	 */
	private void initAttributes(List<GluuSAMLTrustRelationship> trustRelationships) {

		List<GluuAttribute> attributes = attributeService.getAllPersonAttributes(GluuUserRole.ADMIN);
		HashMap<String, GluuAttribute> attributesByDNs = attributeService.getAttributeMapByDNs(attributes);

		GluuAttribute uid = attributeService.getAttributeByName(OxConstants.UID);

		// Load attributes definition
		for (GluuSAMLTrustRelationship trustRelationship : trustRelationships) {

			// Add first attribute uid
			List<String> oldAttributes = trustRelationship.getReleasedAttributes();
			List<String> releasedAttributes = new ArrayList<String>();

			if (oldAttributes != null) {
				releasedAttributes.addAll(oldAttributes);
			}

			if (uid != null) {
				if (releasedAttributes.remove(uid.getDn())) {
					releasedAttributes.add(0, uid.getDn());
				}
			}

			// Resolve custom attributes by DNs
			trustRelationship.setReleasedCustomAttributes(
					attributeService.getCustomAttributesByAttributeDNs(releasedAttributes, attributesByDNs));

			// Set attribute meta-data
			attributeService.setAttributeMetadata(trustRelationship.getReleasedCustomAttributes(), attributes);
		}
	}

	/*
	 * Prepare trustRelationships to generate files
	 */
	private HashMap<String, Object> initTrustParamMap(List<GluuSAMLTrustRelationship> trustRelationships) {

		log.trace("Starting trust parameters map initialization.");

		HashMap<String, Object> trustParams = new HashMap<String, Object>();

		// Metadata signature verification engines
		// https://wiki.shibboleth.net/confluence/display/SHIB2/IdPTrustEngine
		List<Map<String, String>> trustEngines = new ArrayList<Map<String, String>>();

		// the map of {inum,number} for easy naming of relying parties.
		Map<String, String> trustIds = new HashMap<String, String>();

		// Trust relationships that are part of some federation
		List<GluuSAMLTrustRelationship> deconstructed = new ArrayList<GluuSAMLTrustRelationship>();

		// the map of {inum,number} for easy naming of federated relying
		// parties.
		Map<String, String> deconstructedIds = new HashMap<String, String>();

		// the map of {inum, {inum, inum, inum...}} describing the federations
		// and TRs defined from them.
		Map<String, List<String>> deconstructedMap = new HashMap<String, List<String>>();

		// entityIds defined in each TR.
		Map<String, List<String>> trustEntityIds = new HashMap<String, List<String>>();

		int id = 1;
		for (GluuSAMLTrustRelationship trustRelationship : trustRelationships) {

			boolean isPartOfFederation = !(trustRelationship.getSpMetaDataSourceType()
					.equals(GluuMetadataSourceType.URI)
					|| trustRelationship.getSpMetaDataSourceType().equals(GluuMetadataSourceType.FILE));

			if (!isPartOfFederation) {

				// Set Id
				trustIds.put(trustRelationship.getInum(), String.valueOf(id++));

				// Set entityId
				String idpMetadataFolder = getIdpMetadataDir();

				File metadataFile = new File(idpMetadataFolder + trustRelationship.getSpMetaDataFN());
				List<String> entityIds = SAMLMetadataParser.getEntityIdFromMetadataFile(metadataFile);

				// if for some reason metadata is corrupted or missing - mark trust relationship
				// INACTIVE
				// user will be able to fix this in UI
				if (entityIds == null) {
					trustRelationship.setStatus(GluuStatus.INACTIVE);
					trustService.updateTrustRelationship(trustRelationship);
					continue;
				}

				trustEntityIds.put(trustRelationship.getInum(), entityIds);

				initProfileConfiguration(trustRelationship);

				if (trustRelationship.getMetadataFilters().get("signatureValidation") != null) {

					Map<String, String> trustEngine = new HashMap<String, String>();

					trustEngine.put("id", "Trust" + StringHelper.removePunctuation(trustRelationship.getInum()));

					trustEngine.put("certPath", getIdpMetadataDir() + "credentials" + File.separator + trustRelationship
							.getMetadataFilters().get("signatureValidation").getFilterCertFileName());

					trustEngines.add(trustEngine);
				}

				// If there is an intrusive filter - push it to the end of the list.
				if (trustRelationship.getGluuSAMLMetaDataFilter() != null) {

					List<String> filtersList = new ArrayList<String>();
					String entityRoleWhiteList = null;
					for (String filterXML : trustRelationship.getGluuSAMLMetaDataFilter()) {

						Document xmlDocument;

						try {

							xmlDocument = xmlService.getXmlDocument(filterXML.getBytes());

						} catch (Exception e) {
							log.error("GluuSAMLMetaDataFilter contains invalid value.", e);
							e.printStackTrace();
							continue;
						}

						if (xmlDocument.getFirstChild().getAttributes().getNamedItem("xsi:type").getNodeValue()
								.equals(FilterService.ENTITY_ROLE_WHITE_LIST_TYPE)) {
							entityRoleWhiteList = filterXML;
							continue;
						}

						filtersList.add(filterXML);
					}

					if (entityRoleWhiteList != null) {
						filtersList.add(entityRoleWhiteList);
					}

					trustRelationship.setGluuSAMLMetaDataFilter(filtersList);
				}

			} else {
				initProfileConfiguration(trustRelationship);

				String federationInum = trustRelationship.getGluuContainerFederation();

				if (deconstructedMap.get(federationInum) == null) {
					deconstructedMap.put(federationInum, new ArrayList<String>());
				}

				deconstructedMap.get(federationInum).add(trustRelationship.getEntityId());
				deconstructed.add(trustRelationship);
				deconstructedIds.put(trustRelationship.getEntityId(), String.valueOf(id++));
			}
		}

		for (String trustRelationshipInum : trustEntityIds.keySet()) {
			List<String> federatedSites = deconstructedMap.get(trustRelationshipInum);
			if (federatedSites != null) {
				trustEntityIds.get(trustRelationshipInum).removeAll(federatedSites);
			}
		}

		trustParams.put("idpCredentialsPath", getIdpMetadataDir() + "credentials" + File.separator);

		trustParams.put("deconstructed", deconstructed);
		trustParams.put("deconstructedIds", deconstructedIds);

		trustParams.put("trustEngines", trustEngines);
		trustParams.put("trusts", trustRelationships);
		trustParams.put("trustIds", trustIds);
		trustParams.put("trustEntityIds", trustEntityIds);

		return trustParams;
	}

	protected void initProfileConfiguration(GluuSAMLTrustRelationship trustRelationship)
			throws FactoryConfigurationError {
		try {
			filterService.parseFilters(trustRelationship);
			profileConfigurationService.parseProfileConfigurations(trustRelationship);
		} catch (Exception e) {
			log.error("Failed to parse stored metadataFilter configuration for trustRelationship "
					+ trustRelationship.getDn(), e);
		}
	}

	private HashMap<String, Object> initAttributeParamMap(List<GluuSAMLTrustRelationship> trustRelationships) {

		HashMap<String, Object> attrParams = new HashMap<String, Object>();

		// Collect attributes
		Set<GluuAttribute> attributes = new HashSet<GluuAttribute>();

		trustRelationships.stream().forEach(tr -> {
			tr.getReleasedCustomAttributes().stream().forEach(ca -> {
				attributes.add(ca.getMetadata());
			});
		});

		return createAttributeMap(attributes);
	}

	private HashMap<String, Object> createAttributeMap(Set<GluuAttribute> attributes) {

		HashMap<String, Object> resolver = new HashMap<String, Object>();
		List<String> attributeNames = new ArrayList<>();

		for (GluuAttribute attribute : attributes)
			attributeNames.add(attribute.getName());

		SchemaEntry schemaEntry = shemaService.getSchema();
		List<AttributeTypeDefinition> attributeTypes = shemaService.getAttributeTypeDefinitions(schemaEntry,
				attributeNames);

		Map<String, String> attributeSAML1Strings = new HashMap<String, String>();
		Map<String, String> attributeSAML2Strings = new HashMap<String, String>();

		for (GluuAttribute metadata : attributes) {
			String attributeName = metadata.getName();
			// urn::dir:attribute-def:$attribute.name
			// urn:oid:$attrParams.attributeOids.get($attribute.name)
			String saml1String = metadata.getSaml1Uri();
			if (StringHelper.isEmpty(saml1String)) {
				boolean standard = metadata.isCustom() || StringHelper.isEmpty(metadata.getUrn())
						|| (!StringHelper.isEmpty(metadata.getUrn())
								&& metadata.getUrn().startsWith("urn:gluu:dir:attribute-def:"));
				saml1String = String.format("urn:%s:dir:attribute-def:%s", standard ? "gluu" : "mace", attributeName);
			}

			attributeSAML1Strings.put(attributeName, saml1String);
			String saml2String = metadata.getSaml2Uri();

			if (StringHelper.isEmpty(saml2String)) {
				AttributeTypeDefinition attributeTypeDefinition = shemaService
						.getAttributeTypeDefinition(attributeTypes, attributeName);
				if (attributeTypeDefinition == null) {
					log.error("Failed to get OID for attribute name {}", attributeName);
					return null;
				}

				saml2String = String.format("urn:oid:%s", attributeTypeDefinition.getOID());
			}

			attributeSAML2Strings.put(attributeName, saml2String);
		}

		resolver.put("attributes", attributes);
		resolver.put("attributeSAML1Strings", attributeSAML1Strings);
		resolver.put("attributeSAML2Strings", attributeSAML2Strings);

		return resolver;
	}

	private HashMap<String, Object> initCASParamMap() {
		HashMap<String, Object> casParams = new HashMap<String, Object>();
		try {
			ShibbolethCASProtocolConfiguration configuration = casService.loadCASConfiguration();
			if (configuration != null) {
				log.info("add ShibbolethCASProtocolConfiguration parameters");
				casParams.put("enabled", configuration.isEnabled());
				casParams.put("extended", configuration.isExtended());
				casParams.put("enableToProxyPatterns", configuration.isEnableToProxyPatterns());
				casParams.put("authorizedToProxyPattern", configuration.getAuthorizedToProxyPattern());
				casParams.put("unauthorizedToProxyPattern", configuration.getAuthorizedToProxyPattern());
			}
		} catch (Exception e) {
			log.error("initCASParamMap() exception", e);
		}
		return casParams;
	}

	public HashMap<String, Object> initAttributeResolverParamMap() {
		List<NameIdConfig> nameIdConfigs = new ArrayList<NameIdConfig>();
		Set<GluuAttribute> nameIdAttributes = new HashSet<GluuAttribute>();

		final LdapOxTrustConfiguration conf = configurationFactory.loadConfigurationFromLdap();
		AttributeResolverConfiguration attributeResolverConfiguration = conf.getAttributeResolverConfig();
		if ((attributeResolverConfiguration != null) && (attributeResolverConfiguration.getNameIdConfigs() != null)) {
			for (NameIdConfig nameIdConfig : attributeResolverConfiguration.getNameIdConfigs()) {
				if (StringHelper.isNotEmpty(nameIdConfig.getSourceAttribute()) && nameIdConfig.isEnabled()) {
					String attributeName = nameIdConfig.getSourceAttribute();
					GluuAttribute attribute = attributeService.getAttributeByName(attributeName);

					nameIdConfigs.add(nameIdConfig);
					nameIdAttributes.add(attribute);
				}
			}
		}

		HashMap<String, Object> attributeResolverParams = createAttributeMap(nameIdAttributes);
		attributeResolverParams.put("configs", nameIdConfigs);
		attributeResolverParams.put("attributes", nameIdAttributes);

		String baseUserDn = personService.getDnForPerson(null);
		String persistenceType = persistenceEntryManager.getPersistenceType(baseUserDn);

		log.debug(">>>>>>>>>> Shibboleth3ConfService.initAttributeResolverParamMap() - Persistance type: '{}'", persistenceType);
		attributeResolverParams.put("persistenceType", persistenceType);
		return attributeResolverParams;
	}

	private VelocityContext prepareVelocityContext(HashMap<String, Object> trustParams,
			HashMap<String, Object> attrParams, HashMap<String, Object> casParams,
			HashMap<String, Object> attrResolverParams, String idpMetadataFolder) {

		VelocityContext context = new VelocityContext();

		context.put("StringHelper", StringHelper.class);
		context.put("salt", configurationFactory.getCryptoConfigurationSalt());

		context.put("trustParams", trustParams);
		context.put("attrParams", attrParams);
		context.put("casParams", casParams);
		context.put("resovlerParams", attrResolverParams);
		context.put("medataFolder", idpMetadataFolder);
		context.put("orgInum", StringHelper.removePunctuation("gluu"));
		context.put("orgSupportEmail", appConfiguration.getOrgSupportEmail());

		String idpUrl = appConfiguration.getIdpUrl();
		context.put("idpUrl", idpUrl);

		String idpHost = idpUrl.replaceAll(":[0-9]*$", "");
		context.put("idpHost", idpHost);

		String spUrl = appConfiguration.getApplicationUrl();
		context.put("spUrl", spUrl);
		String spHost = spUrl.replaceAll(":[0-9]*$", "").replaceAll("^.*?//", "");
		context.put("spHost", spHost);
		String gluuSPInum = configurationService.getConfiguration().getGluuSPTR();
		GluuSAMLTrustRelationship gluuSP = trustService.getRelationshipByInum(gluuSPInum);
		if (gluuSP == null) {
			gluuSP = new GluuSAMLTrustRelationship();
		}
		String gluuSPEntityId = gluuSP.getEntityId();
		context.put("gluuSPEntityId", gluuSPEntityId);
		String regx = "\\s*(=>|,|\\s)\\s*";// white spaces or comma

		String ldapUrls[] = appConfiguration.getIdpLdapServer().split(regx);
		String ldapUrl = "";
		if (ldapUrls != null) {

			for (String ldapServer : ldapUrls) {
				if (ldapUrl.length() > 1)
					ldapUrl = ldapUrl + " ";
				ldapUrl = ldapUrl + appConfiguration.getIdpLdapProtocol() + "://" + ldapServer;
			}

		} else {
			ldapUrl = appConfiguration.getIdpLdapProtocol() + "://" + appConfiguration.getIdpLdapServer();
		}

		context.put("ldapUrl", ldapUrl);
		context.put("bindDN", appConfiguration.getIdpBindDn());

		try {
			context.put("ldapPass", encryptionService.decrypt(appConfiguration.getIdpBindPassword()));
		} catch (EncryptionException e) {
			log.error("Failed to decrypt bindPassword", e);
			e.printStackTrace();
		}

		context.put("securityKey", appConfiguration.getIdpSecurityKey());
		context.put("securityCert", appConfiguration.getIdpSecurityCert());

		try {
			context.put("securityKeyPassword", encryptionService.decrypt(appConfiguration.getIdpSecurityKeyPassword()));
		} catch (EncryptionException e) {
			log.error("Failed to decrypt idp.securityKeyPassword", e);
			e.printStackTrace();
		}

		return context;
	}

	public String getIdpMetadataFilePath() {
		String filePath = getIdpMetadataDir() + SHIB3_IDP_IDP_METADATA_FILE;
		return filePath;
	}

	public String getIdpConfAuthnDir() {
		return appConfiguration.getShibboleth3IdpRootDir() + File.separator + SHIB3_IDP_CONF_FOLDER + File.separator
				+ SHIB3_IDP_AUNTHN_FOLDER + File.separator;
	}

	public String getIdpConfDir() {
		return appConfiguration.getShibboleth3IdpRootDir() + File.separator + SHIB3_IDP_CONF_FOLDER + File.separator;
	}

	public String getIdpMetadataDir() {
		return appConfiguration.getShibboleth3IdpRootDir() + File.separator + SHIB3_IDP_METADATA_FOLDER
				+ File.separator;
	}

	public String getIdpMetadataTempDir() {
		return appConfiguration.getShibboleth3IdpRootDir() + File.separator
				+ Shibboleth3ConfService.SHIB3_IDP_TEMPMETADATA_FOLDER + File.separator;
	}

	public String getSpMetadataFilePath(String spMetaDataFN) {

		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			throw new InvalidConfigurationException(
					"Failed to return SP meta-data file due to undefined IDP root folder");
		}

		String idpMetadataFolder = getIdpMetadataDir();
		return idpMetadataFolder + spMetaDataFN;
	}

	public String getSpNewMetadataFileName(GluuSAMLTrustRelationship trustRel) {
		return getSpNewMetadataFileName(trustRel.getInum());
	}

	public String getSpNewMetadataFileName(String inum) {

		String relationshipInum = StringHelper.removePunctuation(inum);
		return String.format(SHIB3_SP_METADATA_FILE_PATTERN, relationshipInum);
	}

	public String saveSpMetadataFile(String spMetadataFileName, byte[] data) {
		try (ByteArrayInputStream bis = new ByteArrayInputStream(data)) {
			return saveSpMetadataFile(spMetadataFileName, bis);
		} catch (IOException e) {
			throw new InvalidConfigurationException(e);
		}
	}

	public String saveSpMetadataFile(String spMetadataFileName, InputStream input) {
		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			String errorMessage = "Failed to save SP meta-data file due to undefined IDP root folder";
			log.error(errorMessage);
			throw new InvalidConfigurationException(errorMessage);
		}
		String idpMetadataTempFolder = getIdpMetadataTempDir();
		String tempFileName = getTempMetadataFilename(idpMetadataTempFolder, spMetadataFileName);
		File spMetadataFile = new File(idpMetadataTempFolder + tempFileName);
		try (FileOutputStream os = FileUtils.openOutputStream(spMetadataFile);) {
			IOUtils.copy(input, os);
			os.flush();
		} catch (IOException ex) {
			log.error("Failed to write SP meta-data file '{}'", spMetadataFile, ex);
			ex.printStackTrace();
			return null;
		}
		return tempFileName;
	}

	private String getTempMetadataFilename(String idpMetadataFolder, String fileName) {
		synchronized (getClass()) {
			File possibleTemp = new File(fileName);
			do {
				possibleTemp = new File(idpMetadataFolder + fileName + INumGenerator.generate(2));
			} while (possibleTemp.exists());
			return possibleTemp.getName();
		}
	}

	public String saveSpMetadataFile(String uri, String spMetadataFileName) {

		if (StringHelper.isEmpty(uri)) {
			return null;
		}

		HTTPFileDownloader.setEasyhttps(new Protocol("https", new EasyCASSLProtocolSocketFactory(), 443));
		String spMetadataFileContent = HTTPFileDownloader.getResource(uri, "application/xml, text/xml", null, null);

		if (StringHelper.isEmpty(spMetadataFileContent)) {
			return null;
		}

		// Save new file
		ByteArrayInputStream is;
		try {
			byte[] spMetadataFileContentBytes = spMetadataFileContent.getBytes("UTF-8");
			is = new ByteArrayInputStream(spMetadataFileContentBytes);
		} catch (UnsupportedEncodingException ex) {
			log.error("saveSpMetadataFile exception", ex);
			ex.printStackTrace();
			return null;
		}

		FileUploadWrapper tmpfileWrapper = new FileUploadWrapper();
		tmpfileWrapper.setStream(is);

		return saveSpMetadataFile(spMetadataFileName, tmpfileWrapper.getStream());
	}

	public String generateSpAttributeMapFile(GluuSAMLTrustRelationship trustRelationship) {

		List<GluuSAMLTrustRelationship> trustRelationships = Arrays.asList(trustRelationship);
		initAttributes(trustRelationships);
		HashMap<String, Object> attrParams = initAttributeParamMap(trustRelationships);

		if (attrParams == null) {
			return null;
		}

		VelocityContext context = prepareVelocityContext(null, attrParams, null, null, null);
		String spAttributeMap = templateService.generateConfFile(SHIB3_SP_ATTRIBUTE_MAP_FILE, context);

		return spAttributeMap;
	}

	public boolean generateSpMetadataFile(GluuSAMLTrustRelationship trustRelationship, String certificate) {

		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			throw new InvalidConfigurationException(
					"Failed to generate SP meta-data file due to undefined IDP root folder");
		}

		String idpMetadataFolder = getIdpMetadataDir();

		// Generate sp-metadata.xml meta-data file
		String spMetadataFileContent = generateSpMetadataFileContent(trustRelationship, certificate);
		if (StringHelper.isEmpty(spMetadataFileContent)) {
			return false;
		}

		if (StringHelper.isEmpty(trustRelationship.getUrl())) {
			log.error("Trust relationship URL is empty");
			return false;
		}

		return templateService.writeConfFile(idpMetadataFolder + trustRelationship.getSpMetaDataFN(),
				spMetadataFileContent);
	}

	public String generateSpMetadataFileContent(GluuSAMLTrustRelationship trustRelationship, String certificate) {

		VelocityContext context = new VelocityContext();
		context.put("certificate", certificate);
		context.put("trustRelationship", trustRelationship);
		context.put("entityId", Util.encodeString(StringHelper.removePunctuation(trustRelationship.getInum())));
		context.put("spHost", trustRelationship.getUrl().replaceFirst("/$", ""));

		// Generate sp-metadata.xml meta-data file
		String spMetadataFileContent = templateService.generateConfFile(SHIB3_IDP_SP_METADATA_FILE, context);
		return spMetadataFileContent;
	}

	public void removeSpMetadataFile(String spMetadataFileName) {

		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			throw new InvalidConfigurationException(
					"Failed to remove SP meta-data file due to undefined IDP root folder");
		}

		String idpMetadataFolder = getIdpMetadataDir();
		File spMetadataFile = new File(idpMetadataFolder + spMetadataFileName);

		if (spMetadataFile.exists()) {
			spMetadataFile.delete();
		}
	}

	public boolean isCorrectSpMetadataFile(String spMetadataFileName) {
		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			throw new InvalidConfigurationException(
					"Failed to check SP meta-data file due to undefined IDP root folder");
		}
		File metadataFile = new File(getIdpMetadataDir() + spMetadataFileName);
		List<String> entityId = SAMLMetadataParser.getSpEntityIdFromMetadataFile(metadataFile);
		return (entityId != null) && !entityId.isEmpty();
	}

	public String getSpAttributeMapFilePath() {
		return appConfiguration.getShibboleth3SpConfDir() + File.separator + SHIB3_SP_ATTRIBUTE_MAP_FILE;
	}

	public String getSpShibboleth3FilePath() {
		return appConfiguration.getShibboleth3SpConfDir() + File.separator + SHIB3_SP_SHIBBOLETH2_FILE;
	}

	public String getSpReadMeResourceName() {
		return SHIB3_SP_READ_ME;
	}

	public String getSpReadMeWindowsResourceName() {
		return SHIB3_SP_READ_ME_WINDOWS;
	}

	public String getPublicCertificate(byte[] cert) {
		if (cert == null) {
			return null;
		}
		try (ByteArrayInputStream bis = new ByteArrayInputStream(cert)) {
			return getPublicCertificate(bis);
		} catch (IOException e) {
			return null;
		}
	}

	public String getPublicCertificate(InputStream is) {
		List<String> lines = null;
		try {
			lines = IOUtils.readLines(new InputStreamReader(is, "US-ASCII"));
		} catch (IOException ex) {
			log.error("Failed to read public key file", ex);
			ex.printStackTrace();
		}

		StringBuilder sb = new StringBuilder();

		boolean keyPart = false;
		for (String line : lines) {
			if (line.startsWith(PUBLIC_CERTIFICATE_END_LINE)) {
				break;
			}
			if (keyPart) {
				if (sb.length() > 0) {
					sb.append("\n");
				}
				sb.append(line);
			}
			if (line.startsWith(PUBLIC_CERTIFICATE_START_LINE)) {
				keyPart = true;
			}
		}

		if (sb.length() == 0) {
			return null;
		}

		return sb.toString();
	}

	public boolean isFederationMetadata(String spMetaDataFN) {
		if (spMetaDataFN == null) {
			return false;
		}
		File spMetaDataFile = new File(getSpMetadataFilePath(spMetaDataFN));
		Document xmlDocument = null;

		try (InputStream is = FileUtils.openInputStream(spMetaDataFile);
				InputStreamReader isr = new InputStreamReader(is, "UTF-8")) {
			try {
				xmlDocument = xmlService.getXmlDocument(new InputSource(isr));
			} catch (Exception ex) {
				log.error("Failed to parse metadata file '{}'", spMetaDataFile.getAbsolutePath(), ex);
				ex.printStackTrace();
			}
		} catch (IOException ex) {
			log.error("Failed to read metadata file '{}'", spMetaDataFile.getAbsolutePath(), ex);
			ex.printStackTrace();
		}

		if (xmlDocument == null) {
			return false;
		}

		XPathFactory factory = XPathFactory.newInstance();
		XPath xPath = factory.newXPath();

		String federationTag = null;
		try {
			federationTag = xPath.compile("count(//*[local-name() = 'EntitiesDescriptor'])").evaluate(xmlDocument);
		} catch (XPathExpressionException ex) {
			log.error("Failed to find IDP metadata file in relaying party file '{}'", spMetaDataFile.getAbsolutePath(),
					ex);
			ex.printStackTrace();
		}

		return Integer.parseInt(federationTag) > 0;
	}

	public boolean generateIdpConfigurationFiles() {

		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			throw new InvalidConfigurationException("Failed to update configuration due to undefined IDP root folder");
		}

		String idpConfFolder = getIdpConfDir();

		// Prepare data for files
		VelocityContext context = new VelocityContext();
		String regx = "\\s*(=>|,|\\s)\\s*";// white spaces or comma
		String ldapUrls[] = appConfiguration.getIdpLdapServer().split(regx);
		String ldapUrl = "";

		if (ldapUrls != null) {

			for (String ldapServer : ldapUrls) {
				if (ldapUrl.length() > 1) {
					ldapUrl = ldapUrl + " ";
				}
				ldapUrl = ldapUrl + appConfiguration.getIdpLdapProtocol() + "://" + ldapServer;
			}

		} else {
			ldapUrl = appConfiguration.getIdpLdapProtocol() + "://" + appConfiguration.getIdpLdapServer();
		}

		String host = ldapUrl;
		String base = appConfiguration.getBaseDN();
		String serviceUser = appConfiguration.getIdpBindDn();
		String serviceCredential = "";
		try {
			serviceCredential = encryptionService.decrypt(appConfiguration.getIdpBindPassword());
		} catch (EncryptionException e) {
			log.error("Failed to decrypt bindPassword", e);
			e.printStackTrace();
		}
		String userField = appConfiguration.getIdpUserFields();
		context.put("host", host);
		context.put("base", base);
		context.put("serviceUser", serviceUser);
		context.put("serviceCredential", serviceCredential);
		context.put("userField", userField);

		// Generate login.config
		String loginConfig = templateService.generateConfFile(SHIB3_IDP_LOGIN_CONFIG_FILE, context);

		boolean result = (loginConfig != null);

		// Write login.config
		result &= templateService.writeConfFile(idpConfFolder + SHIB3_IDP_LOGIN_CONFIG_FILE, loginConfig);

		return result;
	}

	public boolean isCorrectMetadataFile(String spMetaDataFN) {

		if (appConfiguration.getShibboleth3FederationRootDir() == null) {
			throw new InvalidConfigurationException(
					"Failed to check meta-data file due to undefined federation root folder");
		}

		String metadataFolder = getIdpMetadataDir();
		File metadataFile = new File(metadataFolder + spMetaDataFN);
		List<String> entityId = SAMLMetadataParser.getEntityIdFromMetadataFile(metadataFile);
		return (entityId != null) && !entityId.isEmpty();
	}

	public void removeMetadataFile(String spMetaDataFN) {

		if (appConfiguration.getShibboleth3FederationRootDir() == null) {
			throw new InvalidConfigurationException(
					"Failed to remove meta-data file due to undefined federation root folder");
		}

		String metadataFolder = getIdpMetadataDir();
		File spMetadataFile = new File(metadataFolder + spMetaDataFN);

		if (spMetadataFile.exists()) {
			spMetadataFile.delete();
		}
	}

	public String getMetadataFilePath(String metadataFileName) {

		if (appConfiguration.getShibboleth3FederationRootDir() == null) {
			throw new InvalidConfigurationException(
					"Failed to return meta-data file due to undefined federation root folder");
		}

		String metadataFolderName = getIdpMetadataDir();
		File metadataFolder = new File(metadataFolderName);
		if (!metadataFolder.exists()) {
			metadataFolder.mkdirs();
		}

		return metadataFolderName + metadataFileName;
	}

	public String getNewMetadataFileName(GluuSAMLFederationProposal federationProposal,
			List<GluuSAMLFederationProposal> allFederationProposals) {

		String relationshipInum = StringHelper.removePunctuation(federationProposal.getInum());
		return String.format(SHIB3_METADATA_FILE_PATTERN, relationshipInum);
	}

	public boolean saveMetadataFile(String metadataFileName, InputStream stream) {

		if (appConfiguration.getShibboleth3FederationRootDir() == null) {
			throw new InvalidConfigurationException(
					"Failed to save meta-data file due to undefined federation root folder");
		}

		String idpMetadataFolderName = getIdpMetadataDir();
		File idpMetadataFolder = new File(idpMetadataFolderName);
		if (!idpMetadataFolder.exists()) {
			idpMetadataFolder.mkdirs();
		}
		File spMetadataFile = new File(idpMetadataFolderName + metadataFileName);
		try (FileOutputStream os = FileUtils.openOutputStream(spMetadataFile)) {
			IOUtils.copy(stream, os);
			os.flush();
		} catch (IOException ex) {
			log.error("Failed to write meta-data file '{}'", spMetadataFile, ex);
			ex.printStackTrace();
			return false;
		} finally {
			IOUtils.closeQuietly(stream);
		}

		return true;
	}

	public boolean saveMetadataFile(String spMetaDataURL, String metadataFileName) {
		if (StringHelper.isEmpty(spMetaDataURL)) {
			return false;
		}

		String metadataFileContent = HTTPFileDownloader.getResource(spMetaDataURL, "application/xml, text/xml", null,
				null);

		if (StringHelper.isEmpty(metadataFileContent)) {
			return false;
		}

		// Save new file
		ByteArrayInputStream is;
		try {
			byte[] metadataFileContentBytes = metadataFileContent.getBytes("UTF-8");
			is = new ByteArrayInputStream(metadataFileContentBytes);
		} catch (UnsupportedEncodingException ex) {
			ex.printStackTrace();
			return false;
		}

		FileUploadWrapper tmpfileWrapper = new FileUploadWrapper();
		tmpfileWrapper.setStream(is);

		return saveMetadataFile(metadataFileName, tmpfileWrapper.getStream());
	}

	/**
	 * Generate metadata files needed for configuration operations: gluuSP metadata
	 * and idp metadata.
	 */
	public boolean generateMetadataFiles() {

		log.info(">>>>>>>>>> IN Shibboleth3ConfService.generateMetadataFiles()...");

		if (appConfiguration.getShibboleth3IdpRootDir() == null) {
			throw new InvalidConfigurationException("Failed to update configuration due to undefined IDP root folder");
		}

		String idpMetadataFolder = getIdpMetadataDir();

		// Prepare data for files
		VelocityContext context = new VelocityContext();
		String idpHost = appConfiguration.getIdpUrl();

		context.put("idpHost", idpHost);
		String domain = idpHost.replaceAll(":[0-9]*$", "").replaceAll("^.*?//", "");
		context.put("domain", domain);

		context.put("orgName", appConfiguration.getOrganizationName());
		context.put("orgShortName", appConfiguration.getOrganizationName());

		try {

			String idpSigningCertificate = FileUtils.readFileToString(new File(appConfiguration.getIdp3SigningCert()))
					.replaceAll("-{5}.*?-{5}", "");
			context.put("idpSigningCertificate", idpSigningCertificate);

		} catch (IOException e) {
			log.error("Unable to get IDP 3 signing certificate from " + appConfiguration.getIdp3SigningCert(), e);
			e.printStackTrace();
			return false;
		}

		try {

			String idpEncryptionCertificate = FileUtils
					.readFileToString(new File(appConfiguration.getIdp3EncryptionCert())).replaceAll("-{5}.*?-{5}", "");
			context.put("idpEncryptionCertificate", idpEncryptionCertificate);

		} catch (IOException e) {
			log.error("Unable to get IDP 3 encryption certificate from " + appConfiguration.getIdp3EncryptionCert(), e);
			e.printStackTrace();
			return false;
		}

		// Generate idp-metadata.xml
		String idpMetadata = templateService.generateConfFile(SHIB3_IDP_IDP_METADATA_FILE, context);

		boolean result = (idpMetadata != null);
		// String idpMetadataName = String.format(SHIB3_IDP_METADATA_FILE_PATTERN,
		// StringHelper.removePunctuation(organizationService.getOrganizationInum()));

		// Write idp-metadata.xml
		result &= templateService.writeConfFile(idpMetadataFolder + SHIB3_IDP_IDP_METADATA_FILE, idpMetadata);

		log.info(">>>>>>>>>> LEAVING Shibboleth3ConfService.generateMetadataFiles()...");

		return result;
	}

	/**
	 * @param stream
	 * @throws IOException
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @return GluuErrorHandler
	 */
	public GluuErrorHandler validateMetadata(InputStream stream)
			throws ParserConfigurationException, SAXException, IOException {
		Schema schema;
		List<InputStream> collect = null;
		try {
			String schemaDir = "META-INF" + File.separator + "shibboleth3" + File.separator + "idp" + File.separator
					+ "schema" + File.separator;
			schemaValidationFileNames = templateService.getClasspathTemplateNames(schemaDir);
			schemaValidationFileNames.remove("schema");
			collect = schemaValidationFileNames.stream()
					.map(e -> ClassUtils.getResourceAsStream(getClass(), schemaDir + e)).collect(Collectors.toList());
			schema = SchemaBuilder.buildSchema(SchemaLanguage.XML, collect.toArray(new InputStream[0]));
		} catch (Exception e) {
			log.info("", e);
			final List<String> validationLog = new ArrayList<String>();
			validationLog.add(GluuErrorHandler.SCHEMA_CREATING_ERROR_MESSAGE);
			validationLog.add(e.getMessage());
			return new GluuErrorHandler(false, true, validationLog);
		} finally {
			if (collect != null) {
				collect.stream().forEach(e -> {
					try {
						e.close();
					} catch (IOException e1) {
						log.error("error closing stream;", e1);
					}
				});
			}
		}
		return XMLValidator.validateMetadata(stream, schema);
	}

	public boolean existsResourceUri(String URLName) {

		try {

			HttpURLConnection.setFollowRedirects(false);
			// note : you may also need
			// HttpURLConnection.setInstanceFollowRedirects(false)
			HttpURLConnection con = (HttpURLConnection) new URL(URLName).openConnection();
			con.setRequestMethod("HEAD");
			return (con.getResponseCode() == HttpURLConnection.HTTP_OK);

		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	public boolean isIdpInstalled() {

		if (appConfiguration.getShibbolethVersion() != null && !appConfiguration.getShibbolethVersion().isEmpty()) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Adds Trust relationship for own shibboleth SP and restarts services after
	 * done.
	 * 
	 * @author �Oleksiy Tataryn�
	 */
	public void addGluuSP() {
		String gluuSPInum = trustService.generateInumForNewTrustRelationship();
		String metadataFN = getSpNewMetadataFileName(gluuSPInum);
		GluuSAMLTrustRelationship gluuSP = new GluuSAMLTrustRelationship();
		gluuSP.setInum(gluuSPInum);
		gluuSP.setDisplayName("gluu SP on configuration");
		gluuSP.setDescription("Trust Relationship for the SP");
		gluuSP.setSpMetaDataSourceType(GluuMetadataSourceType.FILE);
		gluuSP.setSpMetaDataFN(metadataFN);
		// TODO:
		gluuSP.setEntityId(StringHelper.removePunctuation(gluuSP.getInum()));
		gluuSP.setUrl(appConfiguration.getApplicationUrl());

		String certificate = "";
		boolean result = false;
		try {
			certificate = FileUtils.readFileToString(new File(appConfiguration.getGluuSpCert()))
					.replaceAll("-{5}.*?-{5}", "");
			generateSpMetadataFile(gluuSP, certificate);
			result = isCorrectSpMetadataFile(gluuSP.getSpMetaDataFN());

		} catch (IOException e) {
			log.error("Failed to gluu SP read certificate file.", e);
		}

		if (result) {
			gluuSP.setStatus(GluuStatus.ACTIVE);
			String inum = gluuSP.getInum();
			String dn = trustService.getDnForTrustRelationShip(inum);

			gluuSP.setDn(dn);
			List<GluuCustomAttribute> customAttributes = new ArrayList<GluuCustomAttribute>();
			List<GluuAttribute> attributes = attributeService.getAllPersonAttributes(GluuUserRole.ADMIN);
			HashMap<String, GluuAttribute> attributesByDNs = attributeService.getAttributeMapByDNs(attributes);
			List<String> customAttributeDNs = new ArrayList<String>();
			List<String> attributeNames = new ArrayList<String>();

			for (String attributeName : appConfiguration.getGluuSpAttributes()) {
				GluuAttribute attribute = attributeService.getAttributeByName(attributeName, attributes);
				if (attribute != null) {
					customAttributeDNs.add(attribute.getDn());
				}
			}

			customAttributes
					.addAll(attributeService.getCustomAttributesByAttributeDNs(customAttributeDNs, attributesByDNs));
			gluuSP.setReleasedCustomAttributes(customAttributes);
			gluuSP.setReleasedAttributes(attributeNames);
			trustService.updateReleasedAttributes(gluuSP);
			trustService.addTrustRelationship(gluuSP);

			GluuConfiguration configuration = configurationService.getConfiguration();
			configuration.setGluuSPTR(gluuSP.getInum());
			configurationService.updateConfiguration(configuration);
		}

		if (result) {
			log.warn("gluuSP EntityID set to " + StringHelper.removePunctuation(gluuSP.getInum())
					+ ". Shibboleth3 configuration should be updated.");
		} else {
			log.error("IDP configuration update failed. GluuSP was not generated.");
		}
	}

	/**
	 * Analyzes trustRelationship metadata to find out if it is federation.
	 * 
	 * @author �Oleksiy Tataryn�
	 * @param trustRelationship
	 * @return
	 */
	public boolean isFederation(GluuSAMLTrustRelationship trustRelationship) {
		// TODO: optimize this method. should not take so long
		return isFederationMetadata(trustRelationship.getSpMetaDataFN());
	}

	/**
	 * @param trustRelationship
	 * @param certificate
	 */
	public void saveCert(GluuSAMLTrustRelationship trustRelationship, String certificate) {
		String sslDirFN = appConfiguration.getShibboleth3IdpRootDir() + File.separator
				+ TrustService.GENERATED_SSL_ARTIFACTS_DIR + File.separator;
		File sslDir = new File(sslDirFN);
		if (!sslDir.exists()) {
			log.debug("creating directory: " + sslDirFN);
			boolean result = sslDir.mkdir();
			if (result) {
				log.debug("DIR created");

			}
		}
		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(new FileWriter(
					sslDirFN + getSpNewMetadataFileName(trustRelationship).replaceFirst("\\.xml$", ".crt")));
			writer.write(Shibboleth3ConfService.PUBLIC_CERTIFICATE_START_LINE + "\n" + certificate
					+ Shibboleth3ConfService.PUBLIC_CERTIFICATE_END_LINE);
		} catch (IOException e) {
		} finally {
			try {
				if (writer != null) {
					writer.close();
				}
			} catch (IOException e) {
			}
		}

	}

	/**
	 * @param trustRelationship
	 * @param key
	 */
	public void saveKey(GluuSAMLTrustRelationship trustRelationship, String key) {

		String sslDirFN = appConfiguration.getShibboleth3IdpRootDir() + File.separator
				+ TrustService.GENERATED_SSL_ARTIFACTS_DIR + File.separator;
		File sslDir = new File(sslDirFN);
		if (!sslDir.exists()) {
			log.debug("creating directory: " + sslDirFN);
			boolean result = sslDir.mkdir();
			if (result) {
				log.debug("DIR created");

			}
		}
		if (key != null) {
			BufferedWriter writer = null;
			try {
				writer = new BufferedWriter(new FileWriter(
						sslDirFN + getSpNewMetadataFileName(trustRelationship).replaceFirst("\\.xml$", ".key")));
				writer.write(key);
			} catch (IOException e) {
			} finally {
				try {
					if (writer != null) {
						writer.close();
					}
				} catch (IOException e) {
				}
			}
		} else {
			File keyFile = new File(
					sslDirFN + getSpNewMetadataFileName(trustRelationship).replaceFirst("\\.xml$", ".key"));
			if (keyFile.exists()) {
				keyFile.delete();
			}
		}

	}
}
