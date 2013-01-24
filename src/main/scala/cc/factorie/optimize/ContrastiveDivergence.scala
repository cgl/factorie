package cc.factorie.optimize

import cc.factorie._
import cc.factorie.la.WeightsTensorAccumulator
import cc.factorie.util.DoubleAccumulator

class ContrastiveDivergenceExample[C](val context: C, val sampler: Sampler[C], val k: Int = 1) extends Example[Model] {
  // NOTE: this assumes that variables are set to the ground truth when this method is called
  def accumulateExampleInto(model: Model, gradient: WeightsTensorAccumulator, value: DoubleAccumulator, margin: DoubleAccumulator): Unit = {
    require(gradient != null, "The ContrastiveDivergenceExample needs a gradient accumulator")
    val proposalDiff = new DiffList
    repeat(k) { proposalDiff ++= sampler.process(context) }
    model.factorsOfFamilyClass[DotFamily](proposalDiff).foreach(f => gradient.accumulate(f.family, f.currentStatistics, -1.0))
    proposalDiff.undo
    model.factorsOfFamilyClass[DotFamily](proposalDiff).foreach(f => gradient.accumulate(f.family, f.currentStatistics))
  }
}

class PersistentContrastiveDivergenceExample[C <: LabeledMutableVar[_]](val context: C, val sampler: Sampler[Var]) extends Example[Model] {
  // NOTE: this assumes that the initial configuration is the ground truth
  def accumulateExampleInto(model: Model, gradient: WeightsTensorAccumulator, value: DoubleAccumulator, margin: DoubleAccumulator): Unit = {
    require(gradient != null, "The PersistentContrastiveDivergenceExample needs a gradient accumulator")
    val groundTruthDiff = new DiffList
    context.setToTarget(groundTruthDiff)
    model.factorsOfFamilyClass[DotFamily](groundTruthDiff).foreach(f => gradient.accumulate(f.family, f.currentStatistics))
    groundTruthDiff.undo
    val proposalDiff = sampler.process(context)
    model.factorsOfFamilyClass[DotFamily](proposalDiff).foreach(f => gradient.accumulate(f.family, f.currentStatistics, -1.0))
  }
}

class ContrastiveDivergenceHingeExample[C <: Var](
  val context: C, val sampler: Sampler[C], val learningMargin: Double = 1.0, val k: Int = 1) extends Example[Model] {
  // NOTE: this assumes that variables are set to the ground truth when this method is called
  def accumulateExampleInto(model: Model, gradient: WeightsTensorAccumulator, value: DoubleAccumulator, margin: DoubleAccumulator): Unit = {
    require(gradient != null, "The ContrastiveDivergenceHingeExample needs a gradient accumulator")
    require(margin != null, "The ContrastiveDivergenceHingeExample needs a margin accumulator")
    val truthScore = model.currentScore(context)
    val proposalDiff = new DiffList
    repeat(k) { proposalDiff ++= sampler.process(context) }
    val proposalScore = model.currentScore(context)
    if (truthScore - proposalScore < learningMargin) {
      model.factorsOfFamilyClass[DotFamily](proposalDiff).foreach(f => gradient.accumulate(f.family, f.currentStatistics, -1.0))
      proposalDiff.undo
      model.factorsOfFamilyClass[DotFamily](proposalDiff).foreach(f => gradient.accumulate(f.family, f.currentStatistics))
      margin.accumulate(truthScore - proposalScore)
    } else
      proposalDiff.undo
  }
}

class PersistentContrastiveDivergenceHingeExample[C <: LabeledMutableVar[_]](
  val context: C, val sampler: Sampler[Var], val learningMargin: Double = 1.0) extends Example[Model] {
  // NOTE: this assumes that the initial configuration is the ground truth
  def accumulateExampleInto(model: Model, gradient: WeightsTensorAccumulator, value: DoubleAccumulator, margin: DoubleAccumulator): Unit = {
    require(gradient != null, "The PersistentContrastiveDivergenceHingeExample needs a gradient accumulator")
    require(margin != null, "The PersistentContrastiveDivergenceHingeExample needs a margin accumulator")
    val proposalDiff = sampler.process(context)
    val currentConfigScore = model.currentScore(context)
    val groundTruthDiff = new DiffList
    context.setToTarget(groundTruthDiff)
    val truthScore = model.currentScore(context)
    if (truthScore - currentConfigScore < learningMargin) {
      model.factorsOfFamilyClass[DotFamily](groundTruthDiff).foreach(f => gradient.accumulate(f.family, f.currentStatistics))
      groundTruthDiff.undo
      model.factorsOfFamilyClass[DotFamily](proposalDiff).foreach(f => gradient.accumulate(f.family, f.currentStatistics, -1.0))
      margin.accumulate(truthScore - currentConfigScore)
    } else
      groundTruthDiff.undo
  }
}