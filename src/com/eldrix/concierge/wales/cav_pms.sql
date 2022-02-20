-- :name fetch-patient-by-crn :? :1
-- :command :query
-- :doc Fetch a patient by hospital identifier, :crn and :type
SELECT People.ID, NHS_NO AS NHS_NUMBER,
to_char(DATE_LAST_CHANGED, 'yyyy/mm/dd hh:mi:ss') as DATE_LAST_MODIFIED,
PATIENT_IDENTIFIERS.PAID_TYPE || PATIENT_IDENTIFIERS.ID as HOSPITAL_ID,
TITLE, People.SURNAME AS LAST_NAME, People.FIRST_FORENAME, People.SECOND_FORENAME, OTHER_FORENAMES,
SEX, to_char(DOB,'yyyy/mm/dd') AS DATE_BIRTH, to_char(DOD,'yyyy/mm/dd') AS DATE_DEATH,
HOME_PHONE_NO, WORK_PHONE_NO,
ADDRESS1,ADDRESS2,ADDRESS3,ADDRESS4, POSTCODE,
to_char(LOCATIONS.DATE_FROM, 'yyyy/mm/dd') as DATE_FROM,
to_char(LOCATIONS.DATE_TO, 'yyyy/mm/dd') as DATE_TO,
COUNTRY_OF_BIRTH, ETHNIC_ORIGIN, MARITAL_STATUS, OCCUPATION, PLACE_OF_BIRTH, PLACE_OF_DEATH,
HEALTHCARE_PRACTITIONERS.national_no AS GP_ID,
EXTERNAL_ORGANISATIONS.national_no AS GPPR_ID
FROM	EXTERNAL_ORGANISATIONS, HEALTHCARE_PRACTITIONERS, LOCATIONS, PEOPLE, PATIENT_IDENTIFIERS
WHERE	PATIENT_IDENTIFIERS.PAID_TYPE = :type
AND PATIENT_IDENTIFIERS.ID = :crn
AND PATIENT_IDENTIFIERS.CRN = 'Y'
AND PATIENT_IDENTIFIERS.MAJOR_FLAG = 'Y'
AND PEOPLE.ID = PATIENT_IDENTIFIERS.PATI_ID
AND LOCATIONS.ORGA_PERS_ID (+) = PEOPLE.ID
AND HEALTHCARE_PRACTITIONERS.PERS_ID (+) = PEOPLE.GP_ID
AND EXTERNAL_ORGANISATIONS.ID (+) = PEOPLE.GPPR_ID
ORDER BY LOCATIONS.DATE_FROM DESC

-- :name fetch-patient-by-nnn :? :1
-- :command :query
-- :doc Fetch a patient by nhs number; parameters: :nnn
SELECT People.ID, NHS_NO AS NHS_NUMBER,
to_char(DATE_LAST_CHANGED, 'yyyy/mm/dd hh:mi:ss') as DATE_LAST_MODIFIED,
PATIENT_IDENTIFIERS.PAID_TYPE || PATIENT_IDENTIFIERS.ID as HOSPITAL_ID,
TITLE, People.SURNAME AS LAST_NAME, People.FIRST_FORENAME, People.SECOND_FORENAME, OTHER_FORENAMES,
SEX, to_char(DOB,'yyyy/mm/dd') AS DATE_BIRTH, to_char(DOD,'yyyy/mm/dd') AS DATE_DEATH,
HOME_PHONE_NO, WORK_PHONE_NO,
ADDRESS1,ADDRESS2,ADDRESS3,ADDRESS4, POSTCODE,
to_char(LOCATIONS.DATE_FROM, 'yyyy/mm/dd') as DATE_FROM,
to_char(LOCATIONS.DATE_TO, 'yyyy/mm/dd') as DATE_TO,
COUNTRY_OF_BIRTH, ETHNIC_ORIGIN, MARITAL_STATUS, OCCUPATION, PLACE_OF_BIRTH, PLACE_OF_DEATH,
HEALTHCARE_PRACTITIONERS.national_no AS GP_ID,
EXTERNAL_ORGANISATIONS.national_no AS GPPR_ID
FROM	EXTERNAL_ORGANISATIONS, HEALTHCARE_PRACTITIONERS, LOCATIONS, PEOPLE, PATIENT_IDENTIFIERS
WHERE	NHS_NO = :nnn
AND PATIENT_IDENTIFIERS.CRN = 'Y'
AND PATIENT_IDENTIFIERS.MAJOR_FLAG = 'Y'
AND PEOPLE.ID = PATIENT_IDENTIFIERS.PATI_ID
AND LOCATIONS.ORGA_PERS_ID (+) = PEOPLE.ID
AND HEALTHCARE_PRACTITIONERS.PERS_ID (+) = PEOPLE.GP_ID
AND EXTERNAL_ORGANISATIONS.ID (+) = PEOPLE.GPPR_ID
ORDER BY LOCATIONS.DATE_FROM DESC

-- :name fetch-patients-for-clinic :? :*
-- :doc Fetch patients for the given clinic(s) by :clinic-code on the given :date-string (YYYY/MM/DD)
SELECT People.ID,
       NHS_NO AS NHS_NUMBER,
       to_char(DATE_LAST_CHANGED, 'yyyy/mm/dd hh:mi:ss') as DATE_LAST_MODIFIED,
       PATIENT_IDENTIFIERS.PAID_TYPE||PATIENT_IDENTIFIERS.ID as HOSPITAL_ID,
       TITLE, 
       People.SURNAME AS LAST_NAME,
       People.FIRST_FORENAME, 
       People.SECOND_FORENAME, 
       OTHER_FORENAMES,
       SEX,
       to_char(DOB,'yyyy/mm/dd') AS DATE_BIRTH,
       to_char(DOD,'yyyy/mm/dd') AS DATE_DEATH,
       HOME_PHONE_NO, 
       WORK_PHONE_NO,
       ADDRESS1,ADDRESS2,ADDRESS3,ADDRESS4,POSTCODE,
       to_char(LOCATIONS.DATE_FROM, 'yyyy/mm/dd') as DATE_FROM,
       to_char(LOCATIONS.DATE_TO, 'yyyy/mm/dd') as DATE_TO,
       GP_ID, GPPR_ID, COUNTRY_OF_BIRTH, ETHNIC_ORIGIN,
       MARITAL_STATUS, OCCUPATION,
       PLACE_OF_BIRTH, PLACE_OF_DEATH,
       HEALTHCARE_PRACTITIONERS.national_no AS GP_ID,
       EXTERNAL_ORGANISATIONS.national_no AS GPPR_ID, 
       BOOKED_SLOTS.CONTACT_TYPE, GetCGRefDesc('CONTACT TYPE', BOOKED_SLOTS.CONTACT_TYPE) contact_type_desc, 
       APPT_SLOTS.START_TIME, APPT_SLOTS.END_TIME 
FROM EXTERNAL_ORGANISATIONS,
     HEALTHCARE_PRACTITIONERS, 
     LOCATIONS, 
     PEOPLE,
     PATIENT_IDENTIFIERS, 
     APPT_SLOTS, 
     BOOKED_SLOTS, 
     ACT_CLIN_SESSIONS,
     OUTPATIENT_CLINICS
WHERE OUTPATIENT_CLINICS.SHORTNAME = :clinic-code
AND ACT_CLIN_SESSIONS.OUCL_ID = OUTPATIENT_CLINICS.OUCL_ID
AND ACT_CLIN_SESSIONS.SESSION_DATE = To_Date(:date-string, 'yyyy/mm/dd')
AND ACT_CLIN_SESSIONS.DATE_CANCD IS NULL
AND BOOKED_SLOTS.ACS_ID = ACT_CLIN_SESSIONS.ACS_ID
AND APPT_SLOTS.APSL_ID = BOOKED_SLOTS.APSL_ID
AND PATIENT_IDENTIFIERS.PATI_ID = BOOKED_SLOTS.PATI_ID
AND PATIENT_IDENTIFIERS.CRN = 'Y'
AND PATIENT_IDENTIFIERS.MAJOR_FLAG = 'Y'
AND PEOPLE.ID = PATIENT_IDENTIFIERS.PATI_ID
AND LOCATIONS.ORGA_PERS_ID (+) = PEOPLE.ID
AND LOCATIONS.DATE_TO (+) IS NULL
AND HEALTHCARE_PRACTITIONERS.PERS_ID (+) = PEOPLE.GP_ID
AND EXTERNAL_ORGANISATIONS.ID (+) = PEOPLE.GPPR_ID

-- :name fetch-admissions-for-patient :? :*
-- :doc Fetch admissions for the given patient
select
Substr(PatientCrn2(b1.pati_id), 1, 7) crn,
pers.surname||', '||pers.title||' '||pers.first_forename name,
c1.id coepID,
c1.con_id,
b1.date_adm,
b1.date_disch,
i1.name ward,
Decode(pers.dod, null, 'N', 'Y' ) deceased,
b1.activity_id,
b1.date_tci,
papa.npi,
papa.pathway_type,
prl.papa_id, b1.refe_id,
b1.pati_id,
papa.health_risk_factor healthrisk
from
patient_pathway papa,
papa_refe_link prl,
people pers,
internal_organisations i1,
specialties s1,
consultant_episodes c1,
ward_stays w1,
   (Select
        inst.pati_id, inst.refe_id, inst.activity_id,
        inst.date_adm, inst.date_disch, inst.date_tci,
        Trim( Leading '0' From Substr(Max(To_Number(To_Char(wast.date_from, 'YYYYMMDD')||LPad(Replace(wast.time_from, ':', ''), 4, '0')||LPad(wast.activity_id, 10, '0'))), 13)) wast_id,
        Trim( Leading '0' From Substr(Max(To_Number(To_Char(coep.date_from, 'YYYYMMDD')||LPad(Replace(coep.time_from, ':', ''), 4, '0')||LPad(coep.id, 10, '0'))), 13)) coep_id
   From
       inpatient_stays inst,
       specialties spec,
       consultant_episodes coep,
       ward_stays wast
   Where
       wast.date_from <= :dateTo And Nvl(wast.date_to, :dateFrom) >= :dateFrom
       And coep.date_from <= :dateTo And Nvl(coep.date_to, :dateFrom) >= :dateFrom
       and inst.activity_id = wast.ais_activity_id
       And inst.activity_id = coep.activity_id
       And inst.status In ('01', '02', '05')
       And wast.ward_id = Nvl(:wardId, wast.ward_id)
       And coep.con_id = Nvl(:conId, coep.con_id)
       And coep.spec_code = Nvl(:pSpecCode, coep.spec_code)
       And inst.activity_id = Nvl(:activityId, inst.activity_id)
       And inst.pati_id = Nvl(:patiId, inst.pati_id)
       And spec.code (+) = coep.spec_code
       And spec.spec_code (+) = Nvl(:pParSpecCode, spec.spec_code (+))
   Group By
       inst.pati_id,
       inst.refe_id,
       inst.activity_id,
       inst.date_adm,
       inst.date_disch,
       inst.date_tci) b1
Where
 w1.activity_id = b1.wast_id
 And c1.id = b1.coep_id
 And s1.code (+) = c1.spec_code
 And i1.id (+) = w1.ward_id
 And pers.id = b1.pati_id
 And prl.refe_id (+) = b1.refe_id
 And papa.id (+) = prl.papa_id
Order by b1.date_adm desc