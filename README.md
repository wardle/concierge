# concierge

Concierge is a clojure re-write of an original golang-based application.

It is designed to provide modules that permit integration with a health and care ecosystem, abstracting services mainly using 
namespaced identifiers and first-class identifier resolution and mapping.

To build an uberjar that can be copied into legacy applications:

clj -M:uberjar; cp target/concierge-full-v0.1.0.jar ~/Dev/rsdb/Frameworks/RSJars/Libraries      