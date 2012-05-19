/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer, FlatHashTable}
import scala.util.{Random,Sorting}
import scala.util.Random
import scala.math
import scala.util.Sorting
import cc.factorie.la._
import cc.factorie.util.Substitutions
import java.io._

/** The only abstract things are _1, _2, statistics(Values), and StatisticsType */
trait Factor2[N1<:Variable,N2<:Variable] extends Factor {
  factor =>
  type NeighborType1 = N1
  type NeighborType2 = N2
  type StatisticsType <: cc.factorie.Statistics
  def _1: N1
  def _2: N2
  def numVariables = 2
  override def variables = IndexedSeq(_1, _2)
  def variable(i:Int) = i match { case 0 => _1; case 1 => _2; case _ => throw new IndexOutOfBoundsException(i.toString) }
  val _variables = Seq(factor._1, factor._2)
  override def values: Values = new Values(_1.value, _2.value)
  case class Values(_1:N1#Value, _2:N2#Value) extends cc.factorie.Values {
    override def apply[B <: Variable](v: B) = get(v).get
    def variables = _variables
    def get[B <: Variable](v: B) =
      if(v == factor._1) Some(_1.asInstanceOf[B#Value])
      else if(v == factor._2) Some(_2.asInstanceOf[B#Value])
      else None
    def contains(v: Variable) = v == factor._1 || v == factor._2
    override def statistics: StatisticsType = Factor2.this.statistics(this)
  }
  def statistics: StatisticsType = statistics(values)
  def statistics(v:Values): StatisticsType

  // For implementing sparsity in belief propagation
  def isLimitingValuesIterator = false
  def limitedDiscreteValuesIterator: Iterator[(Int,Int)] = Iterator.empty

  /** valuesIterator in style of specifying fixed neighbors */
  def valuesIterator(fixed: Assignment): Iterator[Values] = {
    val fixed1 = fixed.contains(_1)
    val fixed2 = fixed.contains(_2)
    if (fixed1 && fixed2) 
      Iterator.single(new Values(fixed(_1), fixed(_2)))
    else if (fixed1) {
      val val1 = fixed(_1)
      if (isLimitingValuesIterator) {
        val d2 = _2.domain.asInstanceOf[DiscreteDomain]
        val intVal1 = val1.asInstanceOf[DiscreteVar].intValue
        limitedDiscreteValuesIterator.filter(t => t._1 == intVal1).map(t => new Values(val1, d2.apply(t._2).asInstanceOf[N2#Value]))
      } else {
        val d2 = _2.domain.asInstanceOf[IterableDomain[N2#Value]]
        d2.values.iterator.map(value => new Values(val1, value))
      }
    } else if (fixed2) {
      val val2 = fixed(_2)
      if (isLimitingValuesIterator) {
        val d1 = _1.domain.asInstanceOf[DiscreteDomain]
        val intVal2 = val2.asInstanceOf[DiscreteVar].intValue
        limitedDiscreteValuesIterator.filter(t => t._2 == intVal2).map(t => new Values(d1.apply(t._1).asInstanceOf[N1#Value], val2))
      } else {
        val d1 = _1.domain.asInstanceOf[IterableDomain[N1#Value]]
        d1.values.iterator.map(value => new Values(value, val2)) 
      }
    } else {
      if (isLimitingValuesIterator) {
        val d1 = _1.domain.asInstanceOf[DiscreteDomain]
        val d2 = _2.domain.asInstanceOf[DiscreteDomain]
        limitedDiscreteValuesIterator.map(t => new Values(d1.apply(t._1).asInstanceOf[N1#Value], d2.apply(t._2).asInstanceOf[N2#Value])) 
      } else {
        val d1 = _1.domain.asInstanceOf[IterableDomain[N1#Value]]
        val d2 = _2.domain.asInstanceOf[IterableDomain[N2#Value]]
        (for (val1 <- d1.values; val2 <- d2.values) yield new Values(val1, val2)).iterator
      }
    }
  }
  
  /** valuesIterator in style of specifying varying neighbors */
  def valuesIterator(varying:Set[Variable]): Iterator[Values] = {
    val varying1 = varying.contains(_1)
    val varying2 = varying.contains(_2)
    if (varying1 && varying2) {
      if (isLimitingValuesIterator) {
        val d1 = _1.domain.asInstanceOf[DiscreteDomain]
        val d2 = _2.domain.asInstanceOf[DiscreteDomain]
        limitedDiscreteValuesIterator.map(t => new Values(d1.apply(t._1).asInstanceOf[N1#Value], d2.apply(t._2).asInstanceOf[N2#Value])) 
      } else {
        val d1 = _1.domain.asInstanceOf[IterableDomain[N1#Value]]
        val d2 = _2.domain.asInstanceOf[IterableDomain[N2#Value]]
        (for (val1 <- d1.values; val2 <- d2.values) yield new Values(val1, val2)).iterator
      }
    } else if (varying1) {
      val val2 = _2.value
      if (isLimitingValuesIterator) {
        val d1 = _1.domain.asInstanceOf[DiscreteDomain]
        val intVal2 = val2.asInstanceOf[DiscreteVar].intValue
        limitedDiscreteValuesIterator.filter(t => t._2 == intVal2).map(t => new Values(d1.apply(t._1).asInstanceOf[N1#Value], val2))
      } else {
        val d1 = _1.domain.asInstanceOf[IterableDomain[N1#Value]]
        d1.values.iterator.map(value => new Values(value, val2))
      }
    } else if (varying2) {
      val val1 = _1.value
      if (isLimitingValuesIterator) {
        val d2 = _2.domain.asInstanceOf[DiscreteDomain]
        val intVal1 = val1.asInstanceOf[DiscreteVar].intValue
        limitedDiscreteValuesIterator.filter(t => t._1 == intVal1).map(t => new Values(val1, d2.apply(t._2).asInstanceOf[N2#Value]))
      } else {
        val d2 = _2.domain.asInstanceOf[IterableDomain[N2#Value]]
        d2.values.iterator.map(value => new Values(val1, value))
      }
    } else {
      Iterator.single(new Values(_1.value, _2.value))
    }
  }

}

/** The only abstract things are _1, _2, and score(Statistics) */
trait FactorWithStatistics2[N1<:Variable,N2<:Variable] extends Factor2[N1,N2] {
  self =>
  type StatisticsType = Statistics
  case class Statistics(_1:N1#Value, _2:N2#Value) extends cc.factorie.Statistics {
    // TODO Make this non-lazy later, when _statisticsDomains can be initialized earlier
    lazy val score = self.score(this)
  }
  def statistics(v:Values) = new Statistics(v._1, v._2).asInstanceOf[StatisticsType]
  def score(s:Statistics): Double
  //def score(t:Tensor2): Double = throw new Error("")
}

trait Family2[N1<:Variable,N2<:Variable] extends FamilyWithNeighborDomains {
  type NeighborType1 = N1
  type NeighborType2 = N2
    /** Override this if you want to matchNeighborDomains */
  def neighborDomain1: Domain[N1#Value] = null
  def neighborDomain2: Domain[N2#Value] = null
  def neighborDomains = Seq(neighborDomain1, neighborDomain2)

  type FactorType = Factor
  type ValuesType = Factor#Values

  // For implementing sparsity in belief propagation
  var isLimitingValuesIterator = false
  lazy val limitedDiscreteValues = new scala.collection.mutable.HashSet[(Int,Int)]
  def addLimitedDiscreteValues(values:Iterable[(Int,Int)]): Unit = limitedDiscreteValues ++= values
  //def limitDiscreteValuesIterator
  
  final case class Factor(_1:N1, _2:N2) extends super.Factor with Factor2[N1,N2] {
    type StatisticsType = Family2.this.StatisticsType
    override def equalityPrerequisite: AnyRef = Family2.this
    override def statistics(values:Values): StatisticsType = thisFamily.statistics(values)
    override def isLimitingValuesIterator = Family2.this.isLimitingValuesIterator
    override def limitedDiscreteValuesIterator: Iterator[(Int,Int)] = limitedDiscreteValues.iterator
    //override def score(t:Tensor2): Double = Family2.this.score(t)
  }
  //def score(t:Tensor2): Double = throw new Error()
  // Cached Statistics
  private var cachedStatisticsArray: Array[StatisticsType] = null
  private var cachedStatisticsHash: HashMap[Product,StatisticsType] = null
  /** It is callers responsibility to clearCachedStatistics if weights or other relevant state changes. */
  override def cachedStatistics(values:Values): StatisticsType =
    if (Template.enableCachedStatistics) values._1 match {
    case v1:DiscreteValue => { 
      values._2 match {
        case v2:DiscreteValue => {
          //println("Template2.cachedStatistics")
          if (cachedStatisticsArray eq null) cachedStatisticsArray = new Array[Statistics](v1.domain.size * v2.domain.size).asInstanceOf[Array[StatisticsType]]
          val i = v1.intValue * v2.domain.dimensionSize + v2.intValue
          if (cachedStatisticsArray(i) eq null) cachedStatisticsArray(i) = values.statistics
          cachedStatisticsArray(i)
        }
        case v2:DiscreteTensorValue if (true /*v2.isConstant*/) => {
          //println("Template2.cachedStatistics")
          if (cachedStatisticsHash eq null) cachedStatisticsHash = new HashMap[Product,StatisticsType] { override protected def initialSize = 512 }
          val i = ((v1.intValue,v2))
          cachedStatisticsHash.getOrElseUpdate(i, values.statistics)
        }
        case _ => values.statistics
      }
    }
    case v1:DiscreteTensorValue if (true /*v1.isConstant*/) => {
      values._2 match {
        case v2:DiscreteValue => {
          if (cachedStatisticsHash eq null) cachedStatisticsHash = new HashMap[Product,StatisticsType]
          val i = ((v2.intValue,v1))
          cachedStatisticsHash.getOrElseUpdate(i, values.statistics)
        }
        case _ => values.statistics
      }
    }
    case _ => values.statistics
  } else values.statistics
  override def clearCachedStatistics: Unit =  { cachedStatisticsArray = null; cachedStatisticsHash = null }
}


trait Statistics2[S1,S2] extends Family {
  self =>
  type StatisticsType = Stat
  final case class Stat(_1:S1, _2:S2) extends super.Statistics {
    lazy val score = self.score(this)
  }
  def score(s:Stat): Double
}

trait TensorStatistics2[S1<:DiscreteTensorValue,S2<:DiscreteTensorValue] extends TensorFamily {
  self =>
  type StatisticsType = Stat
  final case class Stat(_1:S1, _2:S2) extends { val tensor: Tensor = Tensor.outer(_1, _2) } with super.Statistics {
    lazy val score = self.score(this)
  }
  def score(s:Stat): Double
}

trait DotStatistics2[S1<:DiscreteTensorValue,S2<:DiscreteTensorValue] extends TensorStatistics2[S1,S2] with DotFamily

trait FamilyWithStatistics2[N1<:Variable,N2<:Variable] extends Family2[N1,N2] with Statistics2[N1#Value,N2#Value] {
  def statistics(values:Values) = Stat(values._1, values._2)
}

trait FamilyWithVectorStatistics2[N1<:DiscreteTensorVar,N2<:DiscreteTensorVar] extends Family2[N1,N2] with TensorStatistics2[N1#Value,N2#Value] {
  def statistics(values:Values) = Stat(values._1, values._2)
}

trait FamilyWithDotStatistics2[N1<:DiscreteTensorVar,N2<:DiscreteTensorVar] extends Family2[N1,N2] with DotStatistics2[N1#Value,N2#Value] {
  def statistics(values:Values) = Stat(values._1, values._2)
  // TODO add method:  score(t:Tensor2): Double
}

