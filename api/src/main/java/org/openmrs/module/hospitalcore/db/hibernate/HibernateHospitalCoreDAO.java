/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.module.hospitalcore.db.hibernate;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.SessionFactory;
import org.hibernate.classic.Session;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.Person;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.DAOException;
import org.openmrs.module.hospitalcore.HospitalCoreService;
import org.openmrs.module.hospitalcore.concept.ConceptModel;
import org.openmrs.module.hospitalcore.concept.Mapping;
import org.openmrs.module.hospitalcore.db.HospitalCoreDAO;
import org.openmrs.module.hospitalcore.model.CoreForm;
import org.openmrs.module.hospitalcore.model.IpdPatientAdmitted;
import org.openmrs.module.hospitalcore.model.OpdTestOrder;
import org.openmrs.module.hospitalcore.model.PatientSearch;
import org.openmrs.module.hospitalcore.model.PatientServiceBillItem;
import org.openmrs.module.hospitalcore.util.DateUtils;
import org.openmrs.module.hospitalcore.util.HospitalCoreConstants;

public class HibernateHospitalCoreDAO implements HospitalCoreDAO {

    SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    SimpleDateFormat formatterExt = new SimpleDateFormat("dd/MM/yyyy");

    private SessionFactory sessionFactory;

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public List<Obs> listObsGroup(Integer personId, Integer conceptId, Integer min, Integer max) throws DAOException {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Obs.class, "obs")
                .add(Restrictions.eq("obs.person.personId", personId))
                .add(Restrictions.eq("obs.concept.conceptId", conceptId)).add(Restrictions.isNull("obs.obsGroup"))
                .addOrder(Order.desc("obs.dateCreated"));
        if (max > 0) {
            criteria.setFirstResult(min).setMaxResults(max);
        }
        List<Obs> list = criteria.list();
        return list;
    }

    public Obs getObsGroupCurrentDate(Integer personId, Integer conceptId) throws DAOException {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Obs.class, "obs")
                .add(Restrictions.eq("obs.person.personId", personId))
                .add(Restrictions.eq("obs.concept.conceptId", conceptId)).add(Restrictions.isNull("obs.obsGroup"));
        String date = formatterExt.format(new Date());
        String startFromDate = date + " 00:00:00";
        String endFromDate = date + " 23:59:59";
        try {
            criteria.add(Restrictions.and(Restrictions.ge("obs.dateCreated", formatter.parse(startFromDate)),
                    Restrictions.le("obs.dateCreated", formatter.parse(endFromDate))));
        } catch (Exception e) {
            // TODO: handle exception
            System.out.println("Error convert date: " + e.toString());
            e.printStackTrace();
        }

        List<Obs> list = criteria.list();
        return CollectionUtils.isNotEmpty(list) ? list.get(0) : null;
    }

    public Integer buildConcepts(List<ConceptModel> conceptModels) {

        HospitalCoreService hcs = Context.getService(HospitalCoreService.class);
        Session session = sessionFactory.getCurrentSession();
        Integer diagnosisNo = 0;
        // Transaction tx = session.beginTransaction();
        // tx.begin();
        for (int i = 0; i < conceptModels.size(); i++) {
            ConceptModel conceptModel = conceptModels.get(i);
            Concept concept = hcs.insertConcept(conceptModel.getConceptDatatype(), conceptModel.getConceptClass(),
                    conceptModel.getName(), "", conceptModel.getDescription());
            System.out.println("concept ==> " + concept.getId());
            for (String synonym : conceptModel.getSynonyms()) {
                hcs.insertSynonym(concept, synonym);
            }

            for (Mapping mapping : conceptModel.getMappings()) {
                hcs.insertMapping(concept, mapping.getSource(), mapping.getSourceCode());
            }

            if (i % 20 == 0) {
                session.flush();
                session.clear();
                System.out.println("Imported " + (i + 1) + " diagnosis (" + (i / conceptModels.size() * 100) + "%)");
            }
            diagnosisNo++;
        }
        return diagnosisNo;
        // tx.commit();
    }

    public List<Patient> searchPatient(String nameOrIdentifier, String gender, int age, int rangeAge, String date,
                                       int rangeDay, String relativeName) throws DAOException {
        List<Patient> patients = new Vector<Patient>();

        String hql = "SELECT DISTINCT p.patient_id,pi.identifier,pn.given_name ,pn.middle_name ,pn.family_name ,ps.gender,ps.birthdate ,EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) age,pn.person_name_id FROM patient p "
                + "INNER JOIN person ps ON p.patient_id = ps.person_id "
                + "INNER JOIN patient_identifier pi ON p.patient_id = pi.patient_id "
                + "INNER JOIN person_name pn ON p.patient_id = pn.person_id "
                + "INNER JOIN person_attribute pa ON p.patient_id= pa.person_id "
                + "INNER JOIN person_attribute_type pat ON pa.person_attribute_type_id = pat.person_attribute_type_id "
                + "WHERE (pi.identifier like '%"
                + nameOrIdentifier
                + "%' "
                + "OR pn.given_name like '"
                + nameOrIdentifier
                + "%' "
                + "OR pn.middle_name like '"
                + nameOrIdentifier
                + "%' "
                + "OR pn.family_name like '"
                + nameOrIdentifier + "%') ";
        if (StringUtils.isNotBlank(gender)) {
            hql += " AND ps.gender = '" + gender + "' ";
        }
        if (StringUtils.isNotBlank(relativeName)) {
            hql += " AND pat.person_attribute_type_id = " + HospitalCoreConstants.NEXT_OF_KIN_PERSON_ATTRIBUTE_ID +  " AND pa.value like '%" + relativeName + "%' ";
        }
        if (StringUtils.isNotBlank(date)) {
            String startDate = DateUtils.getDateFromRange(date, -rangeDay) + " 00:00:00";
            String endtDate = DateUtils.getDateFromRange(date, rangeDay) + " 23:59:59";
            hql += " AND ps.birthdate BETWEEN '" + startDate + "' AND '" + endtDate + "' ";
        }
        if (age > 0) {
            hql += " AND EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) >=" + (age - rangeAge)
                    + " AND EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) <= " + (age + rangeAge) + " ";
        }
        hql += " ORDER BY p.patient_id ASC";

        Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
        List l = query.list();
        if (CollectionUtils.isNotEmpty(l))
            for (Object obj : l) {
                Object[] obss = (Object[]) obj;
                if (obss != null && obss.length > 0) {
                    Person person = new Person((Integer) obss[0]);
                    PersonName personName = new PersonName((Integer) obss[8]);
                    personName.setGivenName((String) obss[2]);
                    personName.setMiddleName((String) obss[3]);
                    personName.setFamilyName((String) obss[4]);
                    personName.setPerson(person);
                    Set<PersonName> names = new HashSet<PersonName>();
                    names.add(personName);
                    person.setNames(names);
                    Patient patient = new Patient(person);
                    PatientIdentifier patientIdentifier = new PatientIdentifier();
                    patientIdentifier.setPatient(patient);
                    patientIdentifier.setIdentifier((String) obss[1]);
                    Set<PatientIdentifier> identifier = new HashSet<PatientIdentifier>();
                    identifier.add(patientIdentifier);
                    patient.setIdentifiers(identifier);
                    patient.setGender((String) obss[5]);
                    patient.setBirthdate((Date) obss[6]);
                    patients.add(patient);
                }

            }
        return patients;
    }

    public List<Patient> searchPatient(String nameOrIdentifier, String gender, int age, int rangeAge, String lastDayOfVisit,
                                       int lastVisit, String relativeName, String maritalStatus, String phoneNumber,
                                       String nationalId, String fileNumber) throws DAOException {
        List<Patient> patients = new Vector<Patient>();
        String[] searchSplit = nameOrIdentifier.split("\\s+");

//        update on the patient search functionality - enhancing the search speed - Issue#reg10
        String hql = "SELECT DISTINCT p.patient_id,pi.identifier,pn.given_name ,pn.middle_name ,pn.family_name ,ps.gender,ps.birthdate,pse.dead,pse.admitted, pse.person_name_id ,EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) age FROM patient p "
                + "INNER JOIN person ps ON p.patient_id = ps.person_id "
                + "INNER JOIN patient_identifier pi ON p.patient_id = pi.patient_id "
                + "INNER JOIN person_name pn ON p.patient_id = pn.person_id "
                + "INNER JOIN person_attribute pa ON p.patient_id= pa.person_id "
                + "INNER JOIN person_attribute_type pat ON pa.person_attribute_type_id = pat.person_attribute_type_id "
                + "INNER JOIN patient_search pse ON p.patient_id = pse.patient_id "
                + "INNER JOIN encounter en ON p.patient_id = en.patient_id "
                + "WHERE (pi.identifier like '%"
                + nameOrIdentifier
                + "%' ";
        //we should avoid alot of possible computations and simulations
        //thus one name could be any name, two names must be the first and second name, and 3 names must be the
        //first , second and last name in that order
        if (searchSplit.length == 1) {
            for (String s : searchSplit) {
                hql += "OR pn.given_name like '%"
                        + s
                        + "%' "
                        + "OR pn.middle_name like '%"
                        + s
                        + "%' "
                        + "OR pn.family_name like '%"
                        + s + "%' ";
            }
        } else if (searchSplit.length == 2) {
            //assumption is that we are searching by first AND (second name OR last name)
            hql += "OR pn.given_name like '%"
                    + searchSplit[0]
                    + "%' "
                    + "AND (pn.middle_name like '%"
                    + searchSplit[1]
                    + "%' "
                    + "OR pn.family_name like '%"
                    + searchSplit[1] + "%') ";

        } else if (searchSplit.length == 3) {
            hql += "OR pn.given_name like '%"
                    + searchSplit[0]
                    + "%' "
                    + "AND pn.middle_name like '%"
                    + searchSplit[1]
                    + "%' "
                    + "AND pn.family_name like '%"
                    + searchSplit[2] + "%'";
        }

        hql += ") ";

        if (StringUtils.isNotBlank(gender)) {
            hql += " AND ps.gender = '" + gender + "' ";
        }
        if (StringUtils.isNotBlank(relativeName)) {
            hql += " AND pat.name = 'Father/Husband Name' AND pa.value like '" + relativeName + "' ";
        }

        if (age > 0) {
            hql += " AND EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) >=" + (age - rangeAge)
                    + " AND EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) <= " + (age + rangeAge) + " ";
        }
        //process last day of visit
        if (StringUtils.isNotBlank(lastDayOfVisit)) {
            hql += " AND (DATE_FORMAT(DATE(en.encounter_datetime),'%d/%m/%Y') = '" + lastDayOfVisit + "')";
        }

        //process range day of visit
        if (lastVisit > 0) {
            hql += " AND (DATEDIFF(NOW(), en.date_created) <= " + lastVisit + ")";

        }

        //process marital status
        if (StringUtils.isNotBlank(maritalStatus)) {
            hql += "AND (pat.name LIKE '%Marital Status%' " + "AND pa.value = '" + maritalStatus + "') ";
        }

        //process national id
        if (StringUtils.isNotBlank(nationalId)) {
            hql += "AND (pat.name LIKE '%National ID%' " + "AND pa.value = '" + nationalId + "') ";
        }

        //process phone number
        if (StringUtils.isNotBlank(phoneNumber)) {
            hql += "AND (pat.name LIKE '%Phone Number%' " + "AND pa.value = '" + phoneNumber + "') ";
        }

        //process patient file number
        if (StringUtils.isNotBlank(fileNumber)) {
            hql += "AND (pat.name LIKE '%File Number%' " + "AND pa.value = '" + fileNumber + "') ";
        }
        hql += " ORDER BY p.patient_id ASC LIMIT 0, 50";

        Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
        List l = query.list();
        if (CollectionUtils.isNotEmpty(l))
            for (Object obj : l) {
                Object[] obss = (Object[]) obj;
                if (obss != null && obss.length > 0) {
                    Person person = new Person((Integer) obss[0]);
                    PersonName personName = new PersonName((Integer) obss[9]);
                    personName.setGivenName((String) obss[2]);
                    personName.setMiddleName((String) obss[3]);
                    personName.setFamilyName((String) obss[4]);
                    personName.setPerson(person);
                    Set<PersonName> names = new HashSet<PersonName>();
                    names.add(personName);
                    person.setNames(names);
                    Patient patient = new Patient(person);
                    PatientIdentifier patientIdentifier = new PatientIdentifier();
                    patientIdentifier.setPatient(patient);
                    patientIdentifier.setIdentifier((String) obss[1]);
                    Set<PatientIdentifier> identifier = new HashSet<PatientIdentifier>();
                    identifier.add(patientIdentifier);
                    patient.setIdentifiers(identifier);
                    patient.setGender((String) obss[5]);
                    patient.setBirthdate((Date) obss[6]);
                    //set the death status
                    if (obss[7] != null) {
                        if (obss[7].toString().equals("1")) {
                            patient.setDead(true);
                        } else if (obss[7].toString().equals("0")) {
                            patient.setDead(false);
                        }
                    }
                    //set the patient admitted or not
                    if (obss[8] != null) {
                        if (obss[8].toString().equals("1")) {
                            patient.setVoided(true);
                        } else if (obss[8].toString().equals("0")) {
                            patient.setVoided(false);
                        }
                    }
                    patients.add(patient);
                }
            }
        return patients;
    }

    @SuppressWarnings("rawtypes")
    public List<Patient> searchPatient(String hql) {
        List<Patient> patients = new Vector<Patient>();
        Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
        List list = query.list();
        if (CollectionUtils.isNotEmpty(list))
            for (Object obj : list) {
                Object[] obss = (Object[]) obj;
                if (obss != null && obss.length > 0) {
                    Person person = new Person((Integer) obss[0]);
                    PersonName personName = new PersonName((Integer) obss[8]);
                    personName.setGivenName((String) obss[2]);
                    personName.setMiddleName((String) obss[3]);
                    personName.setFamilyName((String) obss[4]);
                    personName.setPerson(person);
                    Set<PersonName> names = new HashSet<PersonName>();
                    names.add(personName);
                    person.setNames(names);
                    Patient patient = new Patient(person);
                    PatientIdentifier patientIdentifier = new PatientIdentifier();
                    patientIdentifier.setPatient(patient);
                    patientIdentifier.setIdentifier((String) obss[1]);
                    Set<PatientIdentifier> identifier = new HashSet<PatientIdentifier>();
                    identifier.add(patientIdentifier);
                    patient.setIdentifiers(identifier);
                    patient.setGender((String) obss[5]);
                    patient.setBirthdate((Date) obss[6]);
                    if (obss.length > 9) {
                        if (obss[9] != null) {
                            if (obss[9].toString().equals("1")) {
                                patient.setDead(true);
                            } else if (obss[9].toString().equals("0")) {
                                patient.setDead(false);
                            }
                        }
                    }
                    //validation on patient is admitted or not
                    if (obss.length > 10) {
                        if (obss[10] != null) {
                            if (obss[10].toString().equals("1")) {
                                patient.setVoided(true);
                            } else if (obss[10].toString().equals("0")) {
                                patient.setVoided(false);
                            }
                        }
                    }
                    patients.add(patient);
                }
            }
        return patients;
    }

    @SuppressWarnings("rawtypes")
    public BigInteger getPatientSearchResultCount(String hql) {
        BigInteger count = new BigInteger("0");
        Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
        List list = query.list();
        if (CollectionUtils.isNotEmpty(list)) {
            count = (BigInteger) list.get(0);
        }
        return count;
    }

    @SuppressWarnings("rawtypes")
    public List<PersonAttribute> getPersonAttributes(Integer patientId) {
        List<PersonAttribute> attributes = new ArrayList<PersonAttribute>();
        String hql = "SELECT pa.person_attribute_type_id, pa.`value` FROM person_attribute pa WHERE pa.person_id = "
                + patientId + " AND pa.voided = 0;";
        Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
        List l = query.list();
        if (CollectionUtils.isNotEmpty(l)) {
            for (Object obj : l) {
                Object[] obss = (Object[]) obj;
                if (obss != null && obss.length > 0) {
                    PersonAttribute attribute = new PersonAttribute();
                    PersonAttributeType type = new PersonAttributeType((Integer) obss[0]);
                    attribute.setAttributeType(type);
                    attribute.setValue((String) obss[1]);
                    attributes.add(attribute);
                }
            }
        }

        return attributes;
    }

    public Encounter getLastVisitEncounter(Patient patient, List<EncounterType> types) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Encounter.class);
        criteria.add(Restrictions.eq("patient", patient));
        criteria.add(Restrictions.in("encounterType", types));
        criteria.addOrder(Order.desc("encounterDatetime"));
        criteria.setFirstResult(0);
        criteria.setMaxResults(1);
        return (Encounter) criteria.uniqueResult();
    }

    //
    // CORE FORM
    //
    public CoreForm saveCoreForm(CoreForm form) {
        return (CoreForm) sessionFactory.getCurrentSession().merge(form);
    }

    public CoreForm getCoreForm(Integer id) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(CoreForm.class);
        criteria.add(Restrictions.eq("id", id));
        return (CoreForm) criteria.uniqueResult();
    }

    @SuppressWarnings("unchecked")
    public List<CoreForm> getCoreForms(String conceptName) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(CoreForm.class);
        criteria.add(Restrictions.eq("conceptName", conceptName));
        return criteria.list();
    }

    @SuppressWarnings("unchecked")
    public List<CoreForm> getCoreForms() {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(CoreForm.class);
        return criteria.list();
    }

    public void deleteCoreForm(CoreForm form) {
        sessionFactory.getCurrentSession().delete(form);
    }

    //
    // PATIENT_SEARCH
    //
    public PatientSearch savePatientSearch(PatientSearch patientSearch) {
        return (PatientSearch) sessionFactory.getCurrentSession().merge(patientSearch);
    }

    /**
     */
    public java.util.Date getLastVisitTime(Patient patientID) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Encounter.class);
        Encounter encounter = new Encounter();
        criteria.add(Restrictions.eq("patient", patientID));

        // Don't trust in system hour so we use encounterId (auto increase)
        criteria.addOrder(Order.desc("encounterId"));

        // return 1 last row
        criteria.setFirstResult(0); // read the first row (desc reading)
        criteria.setMaxResults(1); // return 1 row

        encounter = (Encounter) criteria.uniqueResult();
        return (java.util.Date) (encounter == null ? null : encounter.getEncounterDatetime());
    }

    //ghanshyam 3-june-2013 New Requirement #1632 Orders from dashboard must be appear in billing queue.User must be able to generate bills from this queue
    public PatientSearch getPatientByPatientId(int patientId) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(PatientSearch.class);
        criteria.add(Restrictions.eq("patientId", patientId));
        return (PatientSearch) criteria.uniqueResult();
    }

    public PatientSearch getPatient(int patientID) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(PatientSearch.class);
        criteria.add(Restrictions.eq("patientId", patientID));
        return (PatientSearch) criteria.uniqueResult();
    }

    public List<Obs> getObsByEncounterAndConcept(Encounter encounter, Concept concept) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Obs.class);
        criteria.add(Restrictions.eq("encounter", encounter));
        criteria.add(Restrictions.eq("concept", concept));
        return criteria.list();
    }

    public PersonAddress getPersonAddress(Person person) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(PersonAddress.class);
        criteria.add(Restrictions.eq("person", person));
        return (PersonAddress) criteria.uniqueResult();
    }

    public OpdTestOrder getOpdTestOrder(Integer opdOrderId) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(OpdTestOrder.class);
        criteria.add(Restrictions.eq("opdOrderId", opdOrderId));
        return (OpdTestOrder) criteria.uniqueResult();
    }

    public PersonAttributeType getPersonAttributeTypeByName(String attributeName) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(PersonAttributeType.class);
        criteria.add(Restrictions.eq("name", attributeName));
        return (PersonAttributeType) criteria.uniqueResult();
    }

    public Obs getObs(Person person, Encounter encounter) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(Obs.class);
        criteria.add(Restrictions.eq("person", person));
        criteria.add(Restrictions.eq("encounter", encounter));
        criteria.add(Restrictions.eq("concept", Context.getConceptService().getConcept("REGISTRATION FEE")));
        return (Obs) criteria.uniqueResult();
    }

    public String getPatientType(Patient patientId) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(IpdPatientAdmitted.class);
        criteria.add(Restrictions.eq("patient", patientId));
        criteria.list();
        if (criteria.list().size() > 0) {
            return "ipdPatient";
        } else {
            return "opdPatient";
        }
    }

    public List<Obs> getObsInstanceForDiagnosis(Encounter encounter, Concept concept) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria
                (Obs.class);
        criteria.add(Restrictions.eq("encounter", encounter));
        criteria.add(Restrictions.eq("concept", concept));
        return criteria.list();
    }

    @Override
    public List<OpdTestOrder> getAllOpdOrdersByDateRange() {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria
                (OpdTestOrder.class);
        criteria.add(Restrictions.eq("billingStatus", 1));
        return criteria.list();
    }

    @Override
    public List<PatientServiceBillItem> getAllPatientServiceBillItemsByDate() {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria
                (PatientServiceBillItem.class);
        return criteria.list();
    }
}
