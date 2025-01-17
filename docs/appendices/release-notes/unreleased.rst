
==================
Unreleased Changes
==================

.. NOTE::

    These changes have not yet been released.

    If you are viewing this document on the Crate.io website, these changes
    reflect what exists on `the master branch`_ in Git. This is where we
    collect changes before they are ready for release.

.. WARNING::

    Unreleased changes may not be ready for general use and could lead to data
    corruption or data loss. You should `back up your data`_ before
    experimenting with unreleased changes.

.. _the master branch: https://github.com/crate/crate
.. _back up your data: https://crate.io/docs/crate/reference/en/latest/admin/snapshots.html

.. DEVELOPER README
.. ================

.. Changes should be recorded here as you are developing CrateDB. When a new
.. release is being cut, changes will be moved to the appropriate release notes
.. file.

.. When resetting this file during a release, leave the headers in place, but
.. add a single paragraph to each section with the word "None".

.. Always cluster items into bigger topics. Link to the documentation whenever feasible.
.. Remember to give the right level of information: Users should understand
.. the impact of the change without going into the depth of tech.

.. rubric:: Table of contents

.. contents::
   :local:


Breaking Changes
================

None


Deprecations
============

None


Changes
=======

- Array comparisons like ``= ANY`` will now automatically unnest the array
  argument to the required dimensions.

  An example::

    cr> SELECT 1 = ANY([ [1, 2], [3, 4] ]);   -- automatic unnesting
    True

    cr> SELECT [1] = ANY([ [1, 2], [3, 4] ]); -- no unnesting
    False


- Added a :ref:`array_unnest <scalar-array_unnest>` scalar function.

- Updated the bundled JDK to 20.0.1+9

- Added support for setting session settings via a ``"options"`` property in the
  startup message for PostgreSQL wire protocol clients.

  An example for JDBC::

    Properties props = new Properties();
    props.setProperty("options", "-c statement_timeout=90000");
    Connection conn = DriverManager.getConnection(url, props);

- Added a :ref:`btrim <scalar-btrim>` scalar function.

- Added support for underscores in numeric literals. Example::

    SELECT 1_000_000;

- Added a :ref:`statement_timeout <conf-session-statement-timeout>` session
  setting and :ref:`cluster setting <statement_timeout>` that allows to set a
  timeout for queries.

- Added ``any_value`` as an alias to the ``arbitrary`` aggregation function, for
  compliance with the SQL2023 standard. Extended the aggregations to support any
  type.

- Added support for ``ORDER BY``, ``MAX``, ``MIN`` and comparison operators on
  expressions of type ``INTERVAL``.

- Improved ``COPY FROM`` retry logic to retry with a delay which increases
  exponentially on temporary network timeout and general network errors.

- Bumped the version of PostgreSQL wire protocol to ``14`` since ``10`` has been
  deprecated.

- Added ``attgenerated`` column to ``pg_catalog.pg_attribute`` table which
  returns ``''`` (empty string) for normal columns and ``'s'`` for
  :ref:`generated columns <ddl-generated-columns>`.

- Allow casts in both forms: ``CAST(<literal or parameter> AS <datatype>)`` and
  ``<literal or parameter>::<datatype>`` for ``LIMIT`` and ``OFFSET`` clauses,

  e.g.::

    SELECT * FROM test OFFSET CAST(? AS long) LIMIT '20'::int

- Added the ``pg_catalog.pg_cursors`` table to expose open cursors.

- Added the
  :ref:`standard_conforming_strings <conf-session-standard_conforming_strings>`
  read-only session setting for improved compatibility with PostgreSQL clients.

- The severity of the node checks on the metadata gateway recovery settings
  has been lowered from `HIGH` to `MEDIUM` as leaving these to default
  or suboptimal values does not translate into data corruption or loss.

Fixes
=====

.. If you add an entry here, the fix needs to be backported to the latest
.. stable branch. You can add a version label (`v/X.Y`) to the pull request for
.. an automated mergify backport.

- Improved an optimization rule to enable index lookups instead of table scans
  in more cases. This is a follow up to a fix in 5.2.7 which fixed a regression
  introduced in 5.2.3.

- Fixed an issue that caused ``DROP TABLE IF EXISTS`` to wrongly return ``1``
  row affected or ``SQLParseException`` (depending on user privileges), when
  called on an existent schema, a non-existent table and with the ``crate``
  catalog prefix, e.g.::

    DROP TABLE IF EXISTS crate.doc.non_existent_table

- Improved output representation of timestamp subtraction, by normalizing to
  bigger units, but no further than days, to be consistent with PostgreSQL
  behavior. e.g::

    SELECT '2022-12-05T11:22:33.123456789+05:30'::timestamp - '2022-12-03T11:22:33.123456789-02:15'::timestamp

  previously would return: ``PT40H15M`` and now returns: ``P1DT16H15M``.

- Improved error message for :ref:`date_bin <date-bin>` scalar function when the
  first argument of :ref:`INTERVAL data type <type-interval>` contains month
  and/or year units.

- Fixed an issue that caused ``AssertionError`` to be thrown when referencing
  previous relations, not explicitly joined, in an join condition, e.g.::

    SELECT * FROM t1
    CROSS JOIN t2
    INNER JOIN t3 ON t3.x = t1.x AND t3.y = t2

