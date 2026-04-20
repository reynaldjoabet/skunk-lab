import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import cats.kernel.Semigroup
import fs2.Stream

import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import skunk._
import skunk.codec.all._
import skunk.data.Type
import skunk.implicits._

object Joins {

  case class Salary(empId: String, month: Int, amount: BigDecimal)
  case class Employee(id: String, name: String, salaries: List[Salary])

  // Clearly to build an Employee from the above 2 tables in the database we would need to do a join and then combine all joined records together for one employee to collect the salaries and make the composition a List[Salary].

  private object EmployeeQueries {

    val empSalaryDecoder = varchar ~ int4 ~ numeric ~ varchar

    val selectAll =
      sql"""
          SELECT e.name,
                 s.month,
                 s.amount,
                 e.id
          FROM employees e, salaries s
          WHERE e.id = s.empId
        """.query(empSalaryDecoder)

    // An implicit join, and it combines the tables in the FROM clause using a comma. The join condition is then specified in the WHERE clause.

  }

  final class EmployeeRepository[M[_]: Sync](
    sessionPool: Resource[M, Session[M]]
  ) {

    import EmployeeQueries._

    def query: M[List[Employee]] = {
      sessionPool.use { session =>
        session
          .execute(selectAll)
          .map(_.groupBy(_._2))
          .map { m =>
            m.map { case (empId, list) =>
                val employeeSalaryJoinRecords =
                  makeSingleEmployeeSalary(empId, list)
                employeeSalaryJoinRecords
                  .tail
                  .foldLeft(employeeSalaryJoinRecords.head)(
                    Semigroup[Employee].combine
                  )
              }
              .toList
          }
      }

    }

    implicit val employeeConcatSemigroup: Semigroup[Employee] = new Semigroup[Employee] {

      def combine(x: Employee, y: Employee): Employee =
        x.copy(salaries = x.salaries ++ y.salaries)

    }

    private def makeSingleEmployeeSalary(
      empId: String,
      empSalaries: List[String ~ Int ~ BigDecimal ~ String]
    ): List[Employee] = {
      empSalaries.map { case ename ~ month ~ amount ~ eid =>
        Employee(eid, ename, List(Salary(eid, month, amount)))
      }
    }

    def query2: M[List[Employee]] = {
      sessionPool.use { session =>
        session
          .execute(selectAll)
          .map(_.groupBy(_._2))
          .map { m =>
            m.map { case (empId, list) =>
                // we know the list is non-empty because it is the result of a groupBy
                val nel = NonEmptyList.fromListUnsafe(list)

                val employeeSalaryJoinRecords =
                  makeSingleEmployeeSalary(empId, nel)
                employeeSalaryJoinRecords.reduce
              }
              .toList
          }
      }
    }

    private def makeSingleEmployeeSalary(
      empId: String,
      empSalaries: NonEmptyList[String ~ Int ~ BigDecimal ~ String]
    ): NonEmptyList[Employee] = {
      empSalaries.map { case ename ~ month ~ amount ~ eid =>
        Employee(eid, ename, List(Salary(eid, month, amount)))
      }
    }

    private def mkEmployee(
      // all the rows for a single employee
      empSalaries: NonEmptyList[String ~ Int ~ BigDecimal ~ String]
    ): Employee = {
      val salaries = empSalaries
        .toList
        .map { case _ ~ month ~ amount ~ eid =>
          Salary(eid, month, amount)
        }

      empSalaries.head match {
        case ename ~ _ ~ _ ~ eid =>
          Employee(eid, ename, salaries)
      }
    }

    def query3: M[List[Employee]] =
      sessionPool.use { session =>
        session
          .execute(selectAll)
          .map(
            _.groupByNel(_._2)
              .map { case (_ @empId, nel) =>
                mkEmployee(nel)
              }
              .toList
          )
      }

  }

  // https://gist.github.com/debasishg/4d90b84b96f6689fc8efae9cff56d2cf
}
