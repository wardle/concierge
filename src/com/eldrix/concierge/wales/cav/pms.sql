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

-- :name fetch-patients-for-clinic :? :*
-- :doc Fetch patients for the given clinic(s) by :clinic-code on the given :date-string (YYYY/MM/DD)
SELECT People.ID, NHS_NO AS NHS_NUMBER,
to_char(DATE_LAST_CHANGED, 'yyyy/mm/dd hh:mi:ss') as
DATE_LAST_MODIFIED,
PATIENT_IDENTIFIERS.PAID_TYPE ||
PATIENT_IDENTIFIERS.ID as HOSPITAL_ID,
TITLE, People.SURNAME AS LAST_NAME,
People.FIRST_FORENAME, People.SECOND_FORENAME, OTHER_FORENAMES,
SEX,
to_char(DOB,'yyyy/mm/dd') AS DATE_BIRTH,
to_char(DOD,'yyyy/mm/dd') AS DATE_DEATH,
HOME_PHONE_NO, WORK_PHONE_NO,
ADDRESS1,ADDRESS2,ADDRESS3,ADDRESS4, POSTCODE,
to_char(LOCATIONS.DATE_FROM, 'yyyy/mm/dd') as DATE_FROM,
to_char(LOCATIONS.DATE_TO, 'yyyy/mm/dd') as DATE_TO,
GP_ID, GPPR_ID, COUNTRY_OF_BIRTH, ETHNIC_ORIGIN,
MARITAL_STATUS, OCCUPATION,
PLACE_OF_BIRTH, PLACE_OF_DEATH,
HEALTHCARE_PRACTITIONERS.national_no AS GP_ID,
EXTERNAL_ORGANISATIONS.national_no AS GPPR_ID
FROM EXTERNAL_ORGANISATIONS,
HEALTHCARE_PRACTITIONERS, LOCATIONS, PEOPLE,
PATIENT_IDENTIFIERS, BOOKED_SLOTS, ACT_CLIN_SESSIONS,
OUTPATIENT_CLINICS
WHERE OUTPATIENT_CLINICS.SHORTNAME = :clinic-code
AND ACT_CLIN_SESSIONS.OUCL_ID = OUTPATIENT_CLINICS.OUCL_ID
AND ACT_CLIN_SESSIONS.SESSION_DATE = To_Date(:date-string, 'yyyy/mm/dd')
AND ACT_CLIN_SESSIONS.DATE_CANCD IS NULL
AND BOOKED_SLOTS.ACS_ID = ACT_CLIN_SESSIONS.ACS_ID
AND PATIENT_IDENTIFIERS.PATI_ID = BOOKED_SLOTS.PATI_ID
AND PATIENT_IDENTIFIERS.CRN = 'Y'
AND PATIENT_IDENTIFIERS.MAJOR_FLAG = 'Y'
AND PEOPLE.ID = PATIENT_IDENTIFIERS.PATI_ID
AND LOCATIONS.ORGA_PERS_ID (+) = PEOPLE.ID
AND LOCATIONS.DATE_TO (+) IS NULL
AND HEALTHCARE_PRACTITIONERS.PERS_ID (+) = PEOPLE.GP_ID
AND EXTERNAL_ORGANISATIONS.ID (+) = PEOPLE.GPPR_ID
ORDER BY LAST_NAME