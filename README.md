# Concierge

[![Scc Count Badge](https://sloc.xyz/github/wardle/concierge)](https://github.com/wardle/concierge/)
[![Scc Cocomo Badge](https://sloc.xyz/github/wardle/concierge?category=cocomo&avg-wage=100000)](https://github.com/wardle/concierge/)

Concierge is a suite of health and care integration modules, abstracting and simplifing integrations with underlying health and care systems. 
	
> A concierge assists guests. This concierge assists clients to integrate into the local health and care ecosystem.

## Background

I built an electronic health record (2007-present day) and found that the most difficult part was integrations with other systems. Unlike many monolithic systems, I wanted to delegate important functionality to a suite of different platform services, so that I could concentrate on creating value. 

Unfortunately, I didn't find a suite of effective platform services, but instead I had to write bespoke integrations to many different data and computing services. 

This work was a great learning experience. 

The core services I needed were:

* terminology - SNOMED-CT.
* reference data - from the NHS "organisational data services"
* demographics - patient identity
* staff identity
* appointments and scheduling
* document repositories

I see these services as foundational; no higher-order clinical data can be interpreted without the context of who, when, why and how.

When I started, there wasn't an off-the-shelf service for terminology or reference data, but they were published via TRUD by NHS Digital. So I originally built these services into the wider EPR, but later realised the importance of spinning them out into their own microservices. You can try out my [terminology server](https://github.com/wardle/go-terminology).

As an EPR not linked to a single organisation, but instead providing services across organisational and disciplinary boundaries, I wanted to hide the underlying complexity from end-users. After all, the patient they are supporting is the same patient, whether it's via a telephone consultation, a GP surgery in Aberystwyth or an outpatient clinic at the University Hospital of Wales.

So I wrote interfaces to multiple services; the best way to describe this was a "war of attrition" with difficulties such as

* difficulty getting access with lots of discussions and interactions with lots of stakeholders
* seemingly arbitrary rules on access  
* no documentation, or out-of-date documentation
* no cohesive structure
* bespoke proprietary web service calls
* sometimes extraordinarily slow response times
* service endpoints that are ephemeral
* no authentication, and authorisation at the level of firewalls
* multiple firewalls, blocking both outgoing and incoming traffic
* logging by the client and not the service 
* no way to do development while actually on the network - so develop on laptop, compile and then use a USB stick to transfer for testing
* lack of readily-available on-demand test environments such as docker images / example source code.

These experiences, over many years, highlighted the need for a set of cohesive platform services - as [outlined on my personal blog](https://wardle.org). 

## Services

Concierge is a new lightweight library and microservice that provides integration services. It is a work-in-progress as I port across my existing (legacy) integrations.

It currently supports the following integrations:

* A wrapper around the NHS Wales' enterprise master patient index (EMPI).
* Staff authentication and lookup via the national directory service (NADEX) to provide staff identity services
* Staff lookup
* Cardiff and Vale Patient Administative System (PAS) integration - for demographics and appointments/scheduling.
* Cardiff and Vale Document Repository service
* Sending text messages using the gov.uk Notify service.

I have also built standalone libraries (embeddable into larger applications) and microservices
to support:

* SNOMED CT - see [hermes](https://github.com/wardle/hermes)
* Organisational data services and postcode lookup / geographical data - see [clods](https://github.com/wardle/clods) and [nhspd](https://github.com/wardle/nhspd)

These individual components are re-combined in my server-side software, with 
support for a generic service mechanism based on identifiers:

* System/value ("identifier") resolution and mapping for the following:
    * NHS Wales patients via namespace https://fhir.wales.nhs.uk/Id/empi-number and organisational (case record numbers) codes for Cardiff and Vale, Swansea, Aneurin Bevan, Cwm Taf Morgannwg, Hywel Dda and Betsi Cadwaladr health boards
    * NHS Wales practitioners via namespace https://fhir.nhs.uk/Id/cymru-user-id using the live active directory
    * User roles via the NHS England SDS role identifier namespace https://fhir.nhs.uk/STU3/CodeSystem/CareConnect-SDSJobRoleName-1
    * SNOMED CT via namespace http://snomed.info/sct
    * Read V2 and CTV mapping to and from SNOMED via namespaces http://read.info/readv2  and http://read.info/ctv3 respectively

I have integrations with the following systems, that are due to be ported to this new application:

* Aneurin Bevan PAS integration
* National document repository service (Welsh Care Records Service)

Future integrations that will be needed will be:

* Welsh Results Reporting Services (WRRS) - although currently permission has not been available to access this service
* Welsh Demographics Service (WDS) - which includes lookup via the NHS England Spine for NHS number tracing

You can think of concierge as a "socket adaptor" making it possible to plug-in your clinical application into a local ecosystem. Concierge abstracts those integrations into a cohesive set of APIs.

# Technical information

This is a `clojure` re-write of an original `golang`-based experiment.

It is designed to provide modules that permit integration with a health and care ecosystem, abstracting services mainly using 
namespaced identifiers and first-class identifier resolution and mapping.


#### Proxy settings

Proxy settings are managed manually. 

You could add proxy settings to deps.edn for an application using this library.

For example:

```
:test/live
           {:extra-paths ["test"]
            :extra-deps  {aero/aero                 {:mvn/version "1.1.6"}
                          com.cognitect/test-runner {:git/url "https://github.com/cognitect-labs/test-runner.git"
                                                     :sha     "b6b3193fcc42659d7e46ecd1884a228993441182"}
                          ch.qos.logback/logback-classic {:mvn/version "1.2.3"}}
            :main-opts   ["-m" "cognitect.test-runner"]
            :jvm-opts ["-Dhttps.proxyHost=137.4.60.101" "-Dhttps.proxyPort=8080"]}
```

Will use the same proxy for all outgoing https requests. 

Instead, most services take optional :proxy-host and :proxy-port parameters to permit à la carte 
selection of a proxy for an individual service. An example is shown in the config.edn file in the
live tests.

#### Development

Run compilation checks

```shell
clj -M:check
```

Linting

```shell
clj -M:lint/eastwood
clj -M:lint/kondo
```

Run unit tests

```shell
clj -M:test/unit
```

Run integration tests

(You'll need to be running within the NHS Wales network - and have the right network access / firewall permissions)

```shell
clj -M:test/live
```

#### Building

To build an uberjar that can be copied into legacy applications:
clj -M:uberjar; cp target/concierge-full-v0.1.0.jar ~/Dev/rsdb/Frameworks/RSJars/Libraries  

#### Use as a library

Add using deps.edn