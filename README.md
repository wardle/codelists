# codelists

`codelists` generates versioned codelists for reproducible research.

You can define codelists using a variety of means, such as

* ICD-10 codes for diagnoses
* ATC codes for drugs
* SNOMED CT expressions in the expression constraint language (ECL).

You can combine these approaches for high sensitivity, or manually derive codelists using hand-crafted ECL for high
specificity.

`codelists` is a simple wrapper around two other services - [hermes](https://github.com/wardle/hermes)
and [dmd](https://github.com/wardle/dmd). I think it is a nice example of composing discrete, but related services
together to give more advanced functionality.

`codelists` operates:

* as a library and so can be embedded within another software package running on the java virtual machine (JVM), written
  in, for example java or clojure.
* as a microservice and so can be used as an API by other software written in any language
* on the command-line, and so can be used for ad-hoc codelist generation as part of your analytics.

The substrate for all codelists is SNOMED CT. That coding system is an ontology and terminology, and not simply a
classification. That means we can use the relationships within SNOMED CT to derive more complete codelists.

You can *realise* a codelist, expanding it to all of its codes. You can also test membership of a given code against a
codelist.

All codelists, by default, expand to include historic codes. This is configurable.

Boolean logic is supported, with arbitrary nesting of your codes using a simple DSL.

A codelist is defined as keys and values in a map.

```json
{
  "info.snomed/ECL": "<<24700007"
} 
```

This defines a codelist using the SNOMED expression constraint language (ECL). While ECL v2.0 supports the use of
historic associations within constraints, I usually recommend ignoring that 'feature' and instead defining whether and
how historic associations are included as part of the API.

SNOMED CT, in the UK, includes the UK drug extension with a 1:1 map between SNOMED identifiers and drugs in the official
UK drug index - dm+d
(dictionary of medicines and devices). That means you *can* use a SNOMED expression to choose drugs:

```json
{
  "info.snomed/ECL": "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
}
```

Note how SNOMED ECL includes simple boolean logic.

But `codelists' supports other namespaced codesystems. For example:

```json
{
  "uk.nhs.dmd/ATC": "L04AX07"
}
```

Will expand to a list of SNOMED identifiers that are mapped to the ATC code L04AX07 and its descendents within the
SNOMED hierarchy.

Different codesystems can be combined using boolean operators and prefix notation:

```json
{
  "or": [
    {
      "uk.nhs.dmd/ATC": "L04AX07"
    },
    {
      "info.snomed/ECL": "(<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
    }
  ]
}
```

This expands the ATC code L04AX07 and supplements with any other product containing DMF as its active ingredient.

If multiple expressions are used, the default is to perform a logical OR. That means this is equivalent to the above
expression:

```json
[
  {"uk.nhs.dmd/ATC": "L04AX07"},
  {"info.snomed/ECL": "(<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"}
]
```
Boolean operators "and", "or" and "not" can be nested arbitrarily for complex expressions.

`codelists` also supports ICD-10.

```json
{
"int.who/ICD10": "G35"
}
```

will expand to include all terms that map to G35, and its descendents.

The operator "not" must be defined within another term, or set of nested terms. 
The result will be the realisation of the first term, or set of nested terms, 
MINUS the realisation of the second term, or set of nested terms.

```json
{
  "int.who/ICD10": "G35",
  "not": {
    "info.snomed/ECL": "<24700007"
  }
}
```

For reproducible research, `codelists` will include information about *how* the codelist
was generated, including the releases of SNOMED CT, dm+d and the different software versions.
It should then be possible to reproduce the content of any codelist.  
