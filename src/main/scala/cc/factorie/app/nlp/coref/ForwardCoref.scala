package cc.factorie.app.nlp.coref

import cc.factorie.app.nlp.wordnet.WordNet
import cc.factorie.app.nlp.{DocumentAnnotatorPipeline, Token, Document, DocumentAnnotator}
import java.util.concurrent.ExecutorService
import cc.factorie.optimize._
import java.io._
import cc.factorie.util._
import scala.collection.mutable.ArrayBuffer
import cc.factorie.app.nlp.phrase.{Phrase, PhraseNumber, PhraseGender, OntonotesPhraseEntityType}
import cc.factorie.app.nlp.coref.mention.ParseAndNerBasedMentionFinding

/**
 * User: apassos
 * Date: 6/27/13
 * Time: 12:25 PM
 */


abstract class ForwardCorefBase extends CorefSystem[Seq[MentionPairLabel]] {
  val options = new Coref1Options
  val model:PairwiseCorefModel
  def tokenAnnotationString(token:Token): String = {
    //val emap = token.document.attr[GenericEntityMap[Mention]]
    token.document.attr[MentionList].filter(mention => mention.phrase.contains(token)) match {
      //case ms:Seq[Mention] if ms.length > 0 => ms.map(m => m.phrase.attr[Mention].categoryValue+":"+m.phrase.indexOf(token)+"e"+emap.getEntity(m)).mkString(", ")
      case _ => "_"
    }
  }


  def preprocessCorpus(trainDocs:Seq[Document]) = {
    //change to _.phrase.isPRO
    val nonPronouns = trainDocs.flatMap(_.coref.mentions.map(_.attr[MentionCharacteristics]).filter(m => !m.isPRO) )
    model.CorefTokenFrequencies.lexicalCounter = new LexicalCounter(LexicalCounter.countWordTypes(nonPronouns,(t) => t.lowerCaseHead))
  }

  def getCorefStructure(coref:WithinDocCoref): Seq[MentionPairLabel] = {
    println("document processed: "+coref.document.name)
    generateFeatures(coref.mentions.toSeq)
  }

  def instantiateModel(optimizer:GradientOptimizer,pool:ExecutorService) = new LeftRightParallelTrainer(optimizer,pool)

  protected def generateFeatures(mentions: Seq[Mention]): Seq[MentionPairLabel] = {
    val labels = new ArrayBuffer[MentionPairLabel]
    for (i <- 0 until mentions.size){
      if(!options.usePronounRules || !mentions(i).attr[MentionCharacteristics].isPRO)
        labels ++= generateTrainingLabelsForOneAnaphor(mentions, i)
    }
    labels
  }

  protected def generateTrainingLabelsForOneAnaphor(orderedMentions: Seq[Mention], anaphorIndex: Int): Seq[MentionPairLabel] = {
    val labels = new ArrayBuffer[MentionPairLabel]
    val m1 = orderedMentions(anaphorIndex)
    var numAntecedents = 0
    var i = anaphorIndex - 1
    while (i >= 0 && (numAntecedents < options.numPositivePairsTrain || !options.pruneNegTrain)) {
      val m2 = orderedMentions(i)
      val label = m1.entity == m2.entity
      if (!pruneMentionPairTraining(m1,m2,label,numAntecedents)) {
        val cl = new MentionPairLabel(model, m1, m2, orderedMentions, label, options=options)
        if(label) numAntecedents += 1
        // merge features from neighboring mentions that are in the same chain as m2
        val mergeables = labels.filter(l => l.mention2.entity == l.mention2.entity)
        mergeFeatures(cl.features, mergeables.map(_.features))
        labels += cl
      }
      i -= 1
    }
    labels
  }

  class LeftRightParallelTrainer(optimizer: GradientOptimizer, pool: ExecutorService, miniBatchSize: Int = 1) extends ParallelTrainer(optimizer,pool){
    def map(in:Seq[MentionPairLabel]): Seq[Example] = MiniBatchExample(miniBatchSize,in.map(label => model.getExample(label,options.slackRescale)))
  }

  def pruneMentionPairTraining(anaphor: Mention,antecedent: Mention,label:Boolean,numAntecedents:Int): Boolean = {
    var skip = false
    val cataphora = antecedent.attr[MentionCharacteristics].isPRO && !anaphor.attr[MentionCharacteristics].isPRO
    if(options.usePronounRules && antecedent.attr[MentionCharacteristics].isPRO) skip = true
    else if(cataphora) {
      if (label && !options.allowPosCataphora || !label && !options.allowNegCataphora) {
        skip = true
      }
    }
    if (label && numAntecedents > 0 && !options.pruneNegTrain) skip = true
    skip
  }

  def mergeFeatures(l: MentionPairFeatures, mergeables: Seq[MentionPairFeatures]) {
    if (options.mergeFeaturesAtAll) {
      assert(l.features.activeCategories.forall(!_.startsWith("NBR")))
      val mergeLeft = ArrayBuffer[MentionPairLabel]()
      mergeables.take(1).diff(mergeLeft).foreach(l.features ++= _.features.mergeableAllFeatures.map("NBRR_" + _))
    }
  }

  def pruneMentionPairTesting(anaphor: Mention,antecedent: Mention): Boolean = {
    var skip = false
    val cataphora = antecedent.phrase.isPronoun && !anaphor.phrase.isPronoun
    if(options.usePronounRules && antecedent.phrase.isPronoun) skip = true
    else if(cataphora || options.allowTestCataphora) skip = true
    skip
  }

  def infer(coref:WithinDocCoref): WithinDocCoref = {
    val mentions = coref.mentions.toSeq
    for (i <- 0 until coref.mentions.size) {
      val m1 = mentions(i)
      val bestCand = getBestCandidate(coref, m1)
      if (bestCand != null) {
        if(bestCand.entity ne null)
          bestCand.entity += m1
        else{
          val entity = coref.newEntity();entity += bestCand;entity += m1
        }
      }
    }
    coref
  }


  def getBestCandidate(coref:WithinDocCoref, m1: Mention): Mention = {
    val candLabels = ArrayBuffer[MentionPairFeatures]()
    val mentions = coref.mentions.toSeq
    var bestCand: Mention = null
    var bestScore = Double.MinValue
    var j = mentions.indexOf(m1)
    var numPositivePairs = 0
    while (j >= 0 && (numPositivePairs < options.numPositivePairsTest || !options.pruneNegTest)) {
      val m2 = mentions(j)
      if (!pruneMentionPairTesting(m1,m2)) {
        val candLabel = new MentionPairFeatures(model, m1, m2, mentions, options=options)
        val mergeables = candLabels.filter(l => l.mention2.entity == l.mention2.entity)
        mergeFeatures(candLabel, mergeables)
        candLabels += candLabel
        val score = model.predict(candLabel.value)//if (m1.isProper && m1.nounWords.forall(m2.nounWords.contains) && m2.nounWords.forall(m1.nounWords.contains)  || options.mergeMentionWithApposition && (m1.isAppositionOf(m2) || m2.isAppositionOf(m1))) Double.PositiveInfinity
        //else model.predict(candLabel.value)
        // Pronouns should always link to something
        if (score > 0.0) {
          numPositivePairs += 1
          if (bestScore <= score) {
            bestCand = m2
            bestScore = score
          }
        }
      }
      j -= 1
    }
    bestCand
  }
}


class ForwardCoref extends ForwardCorefBase {
  val model = new BaseCorefModel
}

class ForwardCorefImplicitConjunctions extends ForwardCorefBase {
  val model = new ImplicitCrossProductCorefModel
}

object ForwardCoref extends ForwardCoref {
  override def prereqAttrs: Seq[Class[_]] = Seq(classOf[OntonotesPhraseEntityType], classOf[PhraseGender], classOf[PhraseNumber])
  deserialize(new DataInputStream(ClasspathURL[ForwardCoref](".factorie").openConnection().getInputStream))
}

// This should only be used when using the NerAndPronounMentionFinder to find mentions
class NerForwardCoref extends ForwardCoref {
  override def prereqAttrs: Seq[Class[_]] = ConllProperNounPhraseFinder.prereqAttrs++ PronounFinder.prereqAttrs ++ Seq(classOf[PhraseGender], classOf[PhraseNumber])
  override def getPhrases(document:Document): Seq[Phrase] = {
    ConllProperNounPhraseFinder.apply(document)
  }
}

object NerForwardCoref extends NerForwardCoref {
  deserialize(new DataInputStream(ClasspathURL[NerForwardCoref](".factorie").openConnection().getInputStream))
}


abstract class CorefSystem[CoreferenceStructure] extends DocumentAnnotator{
  val model:CorefModel
  val options:Coref1Options
  def prereqAttrs: Seq[Class[_]] = Seq(classOf[OntonotesPhraseEntityType], classOf[PhraseGender], classOf[PhraseNumber])
  def postAttrs = Seq(classOf[WithinDocEntity])
  def process(document: Document) = {
    val phrases = getPhrases(document)
    //assertSorted(mentions)
    val coref = new WithinDocCoref(document)
    phrases.foreach(coref.addMention)
    document.attr += infer(coref)
    document
  }

  def getPhrases(document:Document): Seq[Phrase] = {
    if(!options.useNonGoldBoundaries){
      assert(document.targetCoref ne null,"Gold Boundaries cannot be used without gold data.")
      document.targetCoref.mentions.map(m => new Phrase(m.phrase.value.chain,m.phrase.start,m.phrase.length,m.phrase.headTokenOffset)).toSeq
    }
    else if(!options.useNERMentions){
      ParseAndNerBasedMentionFinding.process(document); document.coref.mentions.map(_.phrase).toSeq
    }
    else  ConllProperNounPhraseFinder.apply(process(document)) ++ PronounFinder.apply(process(document))
  }

  def preprocessCorpus(trainDocs: Seq[Document]): Unit
  def getCorefStructure(coref:WithinDocCoref):CoreferenceStructure
  def instantiateModel(optimizer: GradientOptimizer,pool:ExecutorService):ParallelTrainer
  def infer(doc:WithinDocCoref): WithinDocCoref

  abstract class ParallelTrainer(optimizer: GradientOptimizer, val pool: ExecutorService) {
    def map(in: CoreferenceStructure): Seq[Example]
    def reduce(states: Iterable[Seq[Example]]) {
      for (examples <- states) {
        val trainer = new OnlineTrainer(model.parameters, optimizer, maxIterations = 1, logEveryN = examples.length - 1)
        trainer.trainFromExamples(examples)
      }
    }
    def runParallel(ins: Seq[CoreferenceStructure]){
      reduce(cc.factorie.util.Threading.parMap(ins, pool)(map))
    }
    def runSequential(ins: Seq[CoreferenceStructure]){
      reduce(ins.map(map))
    }
  }

  def train(trainDocs: Seq[Document], testDocs: Seq[Document], wn: WordNet, rng: scala.util.Random, saveModelBetweenEpochs: Boolean,saveFrequency: Int,filename: String, learningRate: Double = 1.0): Double =  {
    //val trainTrueMaps = trainDocs.map(d => d.name -> model.generateTrueMap(d.attr[MentionList])).toMap
    val optimizer = if (options.useAverageIterate) new AdaGrad(learningRate) with ParameterAveraging else if (options.useMIRA) new AdaMira(learningRate) with ParameterAveraging else new AdaGrad(rate = learningRate)
    for(doc <- trainDocs; mention <- doc.coref.mentions) mention.attr += new MentionCharacteristics(mention)
    for(doc <- testDocs; mention <- doc.coref.mentions) mention.attr += new MentionCharacteristics(mention)
    for(doc <- trainDocs; mention <- doc.targetCoref.mentions) mention.attr += new MentionCharacteristics(mention)
    preprocessCorpus(trainDocs)
    val trainingFormat: Seq[CoreferenceStructure] = trainDocs.map(doc => getCorefStructure(doc.targetCoref))
    val pool = java.util.concurrent.Executors.newFixedThreadPool(options.numThreads)
    model.MentionPairFeaturesDomain.freeze()
    var accuracy = 0.0
    try {
      val trainer = instantiateModel(optimizer, pool)
      for (iter <- 0 until options.numTrainingIterations) {
        val shuffledDocs = rng.shuffle(trainingFormat)
        val batches = shuffledDocs.grouped(options.featureComputationsPerThread*options.numThreads).toSeq
        for ((batch, b) <- batches.zipWithIndex) {
          if (options.numThreads > 1) trainer.runParallel(batch)
          else trainer.runSequential(batch)
        }
        if (!model.MentionPairFeaturesDomain.dimensionDomain.frozen) model.MentionPairFeaturesDomain.dimensionDomain.freeze()
        optimizer match {case o: ParameterAveraging => o.setWeightsToAverage(model.parameters) }
        println("Train docs")
        doTest(trainDocs.take((trainDocs.length*options.trainPortionForTest).toInt), wn, "Train")
        println("Test docs")
        accuracy = doTest(testDocs, wn, "Test")

        if(saveModelBetweenEpochs && iter % saveFrequency == 0)
          serialize(filename + "-" + iter)
        optimizer match {case o: ParameterAveraging => o.unSetWeightsToAverage(model.parameters) }
      }
      optimizer match {case o: ParameterAveraging => o.setWeightsToAverage(model.parameters) }
      accuracy
    } finally {
      pool.shutdown()
    }
  }



  def assertSorted(mentions: Seq[Mention]): Unit = {
    for(i <- 0 until mentions.length)
      assert(mentions(i).phrase.tokens.head.stringStart >= mentions(i-1).phrase.tokens.head.stringStart, "the mentions are not sorted by their position in the document. Error at position " +i+ " of " + mentions.length)
  }

  class CorefTester(scorer: CorefScorer, scorerMutex: Object, val pool: ExecutorService){
    def map(doc: Document): Unit = {
       val fId = doc.name
      assert(doc.targetCoref ne null,"Cannot perform test on document without test key.")
      val trueCoref = doc.targetCoref
      val predCoref = doc.coref
      //should the tester really call mention detection every time?
      //val newCoref = new WithinDocCoref(doc)
      //assert(predCoref.mentions.forall(m=>m.attr[MentionCharacteristics] ne null))
      assert(trueCoref.mentions.forall(m=>m.phrase.attr[OntonotesPhraseEntityType] ne null))
      assert(predCoref.mentions.forall(m=>m.phrase.attr[OntonotesPhraseEntityType] ne null))

      //this will add any old mention from our predicted mentions which may include entity information
      //trueCoref.mentions.foreach{m => predCoref.addMention(m.phrase).phrase.attr += m.phrase.attr[OntonotesPhraseEntityType];newCoref.mention(m.phrase).attr += m.attr[PhraseGender];newCoref.mention(m.phrase).attr += m.attr[PhraseNumber]}
      //predCoref.mentions.foreach{m => newCoref.mention(m.phrase).attr += m.attr[MentionCharacteristics]}

      predCoref.mentions.foreach(m=>m._setEntity(null))
      doc.attr += infer(predCoref)
      //doc.coref = newCoref
      val gtMentions = trueCoref.mentions.toSet
      val predMentions = predCoref.mentions.toSet
      println("One run")
      predCoref.mentions.zipWithIndex.foreach{case(mention,idx) => if(mention.entity eq null) predCoref.newEntity() += mention}
      val nm = predMentions.size
      gtMentions.seq.toSeq.zipWithIndex.foreach{
        case (gtM, idx) =>
          if(!predCoref.mentions.exists(m => m.phrase.value == gtM.phrase.value)) predCoref.addMention(gtM.phrase,nm+idx)
      }
      //gtMentions.map(_.phrase.value).diff(predMentions.map(_.phrase.value)).seq.toSeq.zipWithIndex.foreach(mi => newCoref.addMention(new Phrase(mi._1.chain,m1._1.start,mi._1.length,mi._1.h),m1._2+nm))
      val pw = PairwiseClusterEvaluation(predCoref, trueCoref)
      val b3 = BCubedNoSingletonClusterEvaluation(predCoref, trueCoref)
      //val muc = CorefEvaluator.MUC.evaluate(predMap, trueMap)

      //      scorerMutex.synchronized {
      //        pwScore.microAppend(pw)
      //        pwScore.macroAppend(pw)
      //        b3Score.microAppend(b3)
      //        b3Score.macroAppend(b3)
      //      }
      scorerMutex.synchronized {
        // scorer.macroMUC.macroAppend(muc)
        scorer.macroPW.macroAppend(pw)
        scorer.microB3.microAppend(b3)
        //scorer.microMUC.microAppend(muc)
        scorer.microPW.microAppend(pw)
      }
    }
    def runParallel(ins: Seq[Document]) = cc.factorie.util.Threading.parMap(ins, pool)(map)
    def runSequential(ins: Seq[(Document)]) = ins.map(map)

  }

  def doTest(testDocs: Seq[Document], wn: WordNet, name: String): Double = {
    val scorer = new CorefScorer
    object ScorerMutex
    val pool = java.util.concurrent.Executors.newFixedThreadPool(options.numThreads)
    var accuracy = 0.0
    try {
      val tester = new CorefTester(scorer, ScorerMutex, pool)
      tester.runParallel(testDocs)
      println("-----------------------")
      println("  * Overall scores")
      scorer.printInhouseScore(name)
      accuracy = scorer.microPW.f1
    } finally pool.shutdown()
    accuracy

  }

  def deserialize(stream: DataInputStream) {
    import cc.factorie.util.CubbieConversions._
    val config = options.getConfigHash
    BinarySerializer.deserialize(config, stream)
    options.setConfigHash(config)
    println("deserializing with config:\n" + options.getConfigHash.iterator.map(x => x._1 + " = " + x._2).mkString("\n"))
    //BinarySerializer.deserialize(model, stream)
    model.deserialize(stream)
    model.MentionPairFeaturesDomain.dimensionDomain.freeze()
    println("model weights 1norm = " + model.parameters.oneNorm)
    stream.close()
  }

  def deserialize(filename: String) {
    deserialize(new DataInputStream(new FileInputStream(filename)))
  }

  def serialize(filename: String) {
    import cc.factorie.util.CubbieConversions._
    println("serializing with config:\n" + options.getConfigHash.iterator.map(x => x._1 + " = " + x._2).mkString("\n"))
    val stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(filename))))
    BinarySerializer.serialize(options.getConfigHash, stream)
    model.serialize(stream)
    println("model weights 1norm = " + model.parameters.oneNorm)
    stream.close()
  }


}

