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

The substrate for all codelists is SNOMED CT. That coding system is an ontology and terminology, and not simply a
classification. That means we can use the relationships within SNOMED CT to derive more complete codelists.

You can *realise* a codelist, expanding it to all of its codes. You can also test membership of a given code against a
codelist.

All codelists, by default, expand to include historic codes. This is configurable.

Boolean logic is supported, with arbitrary nesting of your codes using a simple DSL.

A codelist is defined as names and values in a map, with the names representing the codesystem
and the values the specification.

```json
{
  "ecl": "<<24700007"
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
  "ecl": "(<<24056811000001108|Dimethyl fumarate|) OR (<<12086301000001102|Tecfidera|) OR (<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
}
```

Note how SNOMED ECL includes simple boolean logic.

But `codelists' supports other namespaced codesystems. For example:

```json
{
  "atc": "L04AX07"
}
```

Will expand to a list of SNOMED identifiers that are mapped to the ATC code L04AX07 and its descendents within the
SNOMED hierarchy.

Different codesystems can be combined using boolean operators and prefix notation:

```json
{
  "or": [
    {
      "atc": "L04AX07"
    },
    {
      "atc": "L04AX08"
    },
    {
      "ecl": "(<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
    }
  ]
}
```

This expands the ATC codes L04AX07 L04AX08 and supplements with any other product containing DMF as its active
ingredient.

If multiple expressions are used, the default is to perform a logical OR. That means this is equivalent to the above
expression:

```json
[
  {
    "atc": "L04AX07"
  },
  {
    "atc": "L04AX08"
  },
  {
    "ecl": "(<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
  }
]
```

Duplicate keys are *not* supported, but multiple expressions using different keys are.

```json
{
  "atc": "L04AX07",
  "ecl": "(<10363601000001109|UK Product| :10362801000001104|Has specific active ingredient| =<<724035008|Dimethyl fumarate|)"
}
```

When no operator is explicitly provided, a logical 'OR' will be performed.

For concision, all keys can take an array (vector), which will be equivalent to using "or" using the same codesystem.

```json
{
  "atc": [
    "L04AX07",
    "L04AX08"
  ]
}
```

Boolean operators "and", "or" and "not" can be nested arbitrarily for complex expressions.

`codelists` also supports ICD-10.

```json
{
  "icd10": "G35.*"
}
```

will expand to include all terms that map to an ICD-10 code with the prefix "G35.", and its descendents.

The operator "not" must be defined within another term, or set of nested terms. The result will be the realisation of
the first term, or set of nested terms, MINUS the realisation of the second term, or set of nested terms.

```json
{
  "icd10": "G35",
  "not": {
    "ecl": "<24700007"
  }
}
```

Or, perhaps a more complex expression:

```json
{
  "or": [
    {
      "icd10": "G35"
    },
    {
      "icd10": "G36"
    }
  ],
  "not": {
    "ecl": "<24700007"
  }
}
```

Or, more concisely:

```json
{
  "icd10": [
    "G35",
    "G36"
  ]
  "not": {
    "ecl": "<24700007"
  }
}
```

These will generate a set of codes that includes "G35" and "G36" but omit "24700007" (multiple sclerosis).

For reproducible research, `codelists` will include information about *how* the codelist was generated, including the
releases of SNOMED CT, dm+d and the different software versions. It should then be possible to reproduce the content of
any codelist.  
