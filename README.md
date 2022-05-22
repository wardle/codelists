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

If you only use the SNOMED CT ECL to define your codelists, then simply use `hermes` directly.
You only need the additional functionality provided by `codelists` if you are building codelists
from a combination of SNOMED CT ECL, ATC codes and ICD-10.

ATC maps are not provided as part of SNOMED CT, but are provided by the UK
dm+d. ICD-10 maps are provided as part of SNOMED CT.

# Getting started

`codelists` depends on two services: [hermes](https://github.com/wardle/hermes) and [dmd](https://github.com/wardle/dmd)
.

`hermes` provides a SNOMED CT terminology server.
`dmd` provides software services around the UK dictionary of medicines and devices (dm+d).

Each of those services uses a file-based 'database'. Each can be run directly from
source code using the clojure command-line tools, or by using the provided
pre-compiled uberjar. You run the latter using java.

In most of my clinical applications, and my data analysis pipelines, I use a combination of all three
services. `codelists` currently does *not* provide an automatic wizard to automatically download and  
build those file-based databases, as I already build and keep a library of multiple versions of each
for other usages.

To prepare `hermes` and `dmd`, you will need
a [TRUD API key from NHS Digital](https://isd.digital.nhs.uk/trud/user/guest/group/0/home),
and use each services' download wizard to automatically download and install the latest distribution(s). That should
take
about 15 minutes, not including download times.

Once you have file-based databases available for `hermes` and `dmd`, simply run:

```shell
clj -M:run serve --hermes ../path/to/snomed-2022-05.db --dmd /path/to/dmd-2022-05-09.db
```

You will then have a locally running HTTP server that can expand codelists.

# Using codelists

You can *realise* a codelist, expanding it to all of its codes. You can also test membership of a given code against a
codelist.

All codelists, by default, expand to include historic codes. This will become
configurable, but is the default for greater sensitivity at the expense of specificity.
Different trade-offs might apply to your specific project.

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

Will expand to a list of SNOMED identifiers that are mapped to the exact match ATC code L04AX07 and its descendents
within the
SNOMED hierarchy.

A SNOMED CT expression in the expression constraint language must be a valid expression.
ICD-10 and ATC codes can be specified as an exact match (e.g. "G35") or as a prefix (e.g. "G3*"). The latter will
match against all codes that begin with "G3".

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
  "icd10": "G35*"
}
```

will expand to include all terms that map to an ICD-10 code with the prefix "G35", and its descendents.

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
      "icd10": "G36.*"
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
    "G36.*"
  ],
  "not": {
    "ecl": "<24700007"
  }
}
```

These will generate a set of codes that includes codes "G35" and any with the prefix "G36." but omit "24700007" (
multiple sclerosis).

You can use wildcards. Here I directly use a running `codelists` HTTP server
to expand a codelist defined as

```json
{
  "atc": "C08*"
}
```
This should give a codelist containing all calcium channel blockers.

```shell
http '127.0.0.1:8080/v1/codelists/expand?s={"atc":"C08*"}'
```
Result:
```json
[
    374049007,
    13764411000001106,
    376841009,
    11160711000001108,
    893111000001107,
    29826211000001109,
    376754006,
    ...
```

For reproducible research, `codelists` will include information about *how* the codelist was generated, including the
releases of SNOMED CT, dm+d and the different software versions. It should then be possible to reproduce the content of
any codelist. At the moment, only the data versions are returned:

```shell
http 127.0.0.1:8080/v1/codelists/status
```

The following metadata will be returned:
```json

{
    "dmd": {
        "releaseDate": "2022-05-05"
    },
    "hermes": [
        "© 2002-2021 International Health Terminology Standards Development Organisation (IHTSDO). All rights reserved. SNOMED CT®, was originally created by The College of American Pathologists. \"SNOMED\" and \"SNOMED CT\" are registered trademarks of the IHTSDO.",
        "32.12.0_20220413000001 UK drug extension",
        "32.12.0_20220413000001 UK clinical extension"
    ]
}

```