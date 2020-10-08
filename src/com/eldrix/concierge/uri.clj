(ns com.eldrix.concierge.uri)

(def namespaces
  "Well-known namespaces. This list is not exhaustive, and namespaces
   not listed here can be used without formally registering their existence."

  {
   ;;
   ;; THESE ARE ALREADY DEFINED NAMESPACES EITHER INTERNATIONALLY OR NATIONALLY
   ;;
   :org.w3.www.2001.01/rdf-schema                    "http://www.w3.org/2000/01/rdf-schema"
   :org.w3.www.2001/XMLSchema                        "http://www.w3.org/2001/XMLSchema"
   :org.w3.www.2002.07/owl                           "http://www.w3.org/2002/07/owl"
   :org.w3.www.ns/prov                               "http://www.w3.org/ns/prov"
   :org.w3.www.2004.02/skos.core                     "http://www.w3.org/2004/02/skos/core#"
   :com.xmlns.foaf-0_1                               "http://xmlns.com/foaf/0.1/"
   :urn.ietf.rfc-3986                                "urn:ietf:rfc:3986" ;; general URI (uniform resource identifier)
   :urn/uuid                                         "urn:uuid" ;; a UUID as per https:// tools.ietf.org / html/rfc4122
   :urn/oid                                          "urn:oid:" ;; prefix for OID identifiers (e.g. urn:oid:1.3.6.1)
   :urn.dicom/uid                                    "urn:dicom:uid"
   :info.snomed/sct                                  "http://snomed.info/sct"
   :org.loinc/Id                                     "http://loinc.org"
   :info.read/readV2                                 "http://read.info/readv2"
   :info.read/ctv3                                   "http://read.info/ctv3"
   :org.gmc-uk/gmc-number                            "https://fhir.hl7.org.uk/Id/gmc-number"
   :uk.org.nmc/nmc-pin                               "https://fhir.hl7.org.uk/Id/nmc-pin" ;; TODO: has anyone decided URIs for other authorities in UK?
   :uk.nhs.id/sds-user-id                            "https://fhir.nhs.uk/Id/sds-user-id"
   :uk.nhs.id/nhs-number                             "https://fhir.nhs.uk/Id/nhs-number"
   :uk.org.hl7/nhs-number-verification-status        "https://fhir.hl7.org.uk/CareConnect-NHSNumberVerificationStatus-1"
   :uk.nhs.stu3.codesystem/sds-job-role              "https://fhir.nhs.uk/STU3/CodeSystem/CareConnect-SDSJobRoleName-1"
   :uk.org.hl7/care-connect-ethnic-category          "https://fhir.hl7.org.uk/CareConnect-EthnicCategory-1"
   :uk.nhs.fhir.id/ods-organization-code             "https://fhir.nhs.uk/Id/ods-organization-code"
   :uk.nhs.fhir.id/ods-site-code                     "https://fhir.nhs.uk/Id/ods-site-code"
   :org.hl7.fhir/composition-status                  "http://hl7.org/fhir/composition-status" ;;see https://www.hl7.org/fhir/valueset-composition-status.html

   ;;;;
   ;;;; I HAVE MADE UP THESE PENDING FORMAL SCRUTINY / DISCUSSION / APPROVAL
   ;;;;
   :wales.nhs.id/cymru-user-id                       "https://fhir.nhs.wales/Id/cymru-user-id"
   :wales.nhs.id/empi-number                         "https://fhir.nhs.wales/empi-number" ;; ephemeral eMPI identifier
   :wales.nhs.sbuhb.id/masterlab                     "https://fhir.sbuhb.nhs.wales/Id/masterlab"
   :wales.nhs.sbuhb.id/east-pas-identifier           "https://fhir.sbuhb.nhs.wales/Id/east-pas-identifier"
   :wales.nhs.sbuhb.id/east-radiology-identifier     "https://fhir.sbuhb.nhs.wales/Id/east-radiology-identifier"
   :wales.nhs.sbuhb.id/west-radiology-identifier     "https://fhir.sbuhb.nhs.wales/Id/west-radiology-idenfifier"
   :wales.nhs.sbuhb.id/new-west-radiology-identifier "https://fhir.sbuhb.nhs.wales/Id/new-west-radiology-idenfifier"
   :wales.nhs.sbuhb.id/pas-identifier                "https://fhir.sbuhb.nhs.wales/Id/pas-identifier"
   :wales.nhs.bcuhb.id/central-pas-identifier        "https://fhir.bcuhb.nhs.wales/Id/central-pas-identifier"
   :wales.nhs.bcuhb.id/east-pas-identifier           "https://fhir.bcuhb.nhs.wales/Id/east-pas-identifier"
   :wales.nhs.bcuhb.id/west-pas-identifier           "https://fhir.bcuhb.nhs.wales/Id/west-pas-identifier"
   :wales.nhs.ctmuhb.id/pas-identifier               "https://fhir.ctmuhb.nhs.wales/Id/pas-identifier"
   :wales.nhs.ctmuhb.id/north-radiology-identifier   "https://fhir.ctmuhb.nhs.wales/Id/north-radiology-identifier"
   :wales.nhs.ctmuhb.id/south-radiology-identifier   "https://fhir.ctmuhb.nhs.wales/Id/south-radiology-identifier"
   :wales.nhs.ctmuhb.id/radiology-identifier         "https://fhir.ctmuhb.nhs.wales/Id/radiology-identifier"
   :wales.nhs.abuhb.id/pas-identifier                "https://fhir.abuhb.nhs.wales/Id/pas-identifier"
   :wales.nhs.abuhb.id/radiology-identifier          "https://fhir.abuhb.nhs.uk/Id/radiology-identifier"
   :wales.nhs.cavuhb.id/pas-identifier               "https://fhir.cavuhb.nhs.wales/Id/pas-identifier"
   :wales.nhs.cavuhb.id/document-identifier          "https://fhir.cavuhb.nhs.wales/Id/document-identifier"
   :wales.nhs.cavuhb.id/clinic-code                  "https://fhir.cavuhb.nhs.wales/Id/clinic-code"
   :wales.nhs.hduhb.id/pas-identifier                "https://fhir.hduhb.nhs.wales/Id/pas-identifier"
   :wales.nhs.trak.id/identifier                     "https://fhir.trak.nhs.wales/Id/identifier"
   :wales.nhs.powys.id/pas-identifier                "https://fhir.powys.nhs.wales/Id/pas-identifier" ;; TODO: does powys even have a PAS?
   })