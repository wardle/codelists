# codelists

`codelists` generates versioned codelists for reproducible research.

You can define codelists using a variety of means, such as 

* ICD-10 codes for diagnoses
* ATC codes for drugs
* SNOMED CT expressions in the expression constraint language (ECL).

You can combine these approaches for high sensitivity, or manually 
derive codelists using hand-crafted ECL for high specificity.

`codelists` is a simple wrapper around two other services - [hermes](https://github.com/wardle/hermes) 
and [dmd](https://github.com/wardle/dmd). I think it is a nice example of composing discrete, but related
services together to give more advanced functionality.

`codelists` operates:

* as a library and so can be embedded within another software package running on the java virtual machine (JVM), written in, for example java or clojure.
* as a microservice and so can be used as an API by other software written in any language
* on the command-line, and so can be used for ad-hoc codelist generation as part of your analytics.


