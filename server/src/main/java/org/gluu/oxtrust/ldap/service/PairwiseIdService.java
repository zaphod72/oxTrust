package org.gluu.oxtrust.ldap.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gluu.oxtrust.model.GluuCustomPerson;
import org.gluu.oxtrust.model.GluuUserPairwiseIdentifier;
import org.gluu.persist.PersistenceEntryManager;
import org.slf4j.Logger;
import org.xdi.util.StringHelper;

public class PairwiseIdService implements IPairwiseIdService, Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = -758342433526960035L;

	@Inject
	private Logger log;

	@Inject
	private PersistenceEntryManager ldapEntryManager;

	@Inject
	private OrganizationService organizationService;

	@Override
	public boolean removePairWiseIdentifier(GluuCustomPerson person, GluuUserPairwiseIdentifier pairwiseIdentifier) {
		try {
			String finalDn = String.format("oxId=%s,ou=pairwiseIdentifiers,", pairwiseIdentifier.getOxId());
			finalDn = finalDn.concat(person.getDn());
			ldapEntryManager.removeRecursively(finalDn);
			return true;
		} catch (Exception e) {
			log.error("", e);
			return false;
		}
	}

	@Override
	public String getDnForPairWiseIdentifier(String oxid, String personInum) {
		String orgDn = organizationService.getDnForOrganization();
		if (!StringHelper.isEmpty(personInum) && StringHelper.isEmpty(oxid)) {
			return String.format("ou=pairwiseIdentifiers,inum=%s,ou=people,%s", personInum, orgDn);
		}
		if (!StringHelper.isEmpty(oxid) && !StringHelper.isEmpty(personInum)) {
			return String.format("oxId=%s,ou=pairwiseIdentifiers,inum=%s,ou=people,%s", oxid, personInum, orgDn);
		}
		return "";
	}

	@Override
	public List<GluuUserPairwiseIdentifier> findAllUserPairwiseIdentifiers(GluuCustomPerson person) {
		try {
			List<GluuUserPairwiseIdentifier> results = ldapEntryManager.findEntries(
					getDnForPairWiseIdentifier(null, person.getInum()), GluuUserPairwiseIdentifier.class, null);
			if (results == null) {
				return new ArrayList<GluuUserPairwiseIdentifier>();
			}
			return results;
		} catch (Exception e) {
			log.warn("Current user don't pairwise identifiers");
			return new ArrayList<GluuUserPairwiseIdentifier>();
		}

	}

}
