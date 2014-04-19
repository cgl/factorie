package cc.factorie.app.nlp.coref

import cc.factorie.util.coref.{GenericEntityMap, CorefEvaluator}
import cc.factorie.app.nlp.{Document, TokenSpan}
import cc.factorie.app.nlp.coref.Mention
import cc.factorie.util.F1Evaluation


/** Various helper methods for printing coreference score Metrics.
    Needs to be overhauled. -akm
    @author Alexandre Passos */
class CorefScorer {
  val macroMUC = new F1Evaluation
  val macroPW = new F1Evaluation
  val microPW = new F1Evaluation
  val microB3 = new F1Evaluation
  val microMUC = new F1Evaluation
  val microCE = new F1Evaluation
  val microCM = new F1Evaluation
  val macroBlanc = new F1Evaluation

  def textualOrder(ts1: TokenSpan, ts2: TokenSpan): Int = {
    val (s1, e1) = (ts1.head.stringStart, ts1.last.stringEnd)
    val (s2, e2) = (ts2.head.stringStart, ts2.last.stringEnd)

    if (s1 == s2) {
      if (e1 == e2) 0
      else e1 - e2
    } else s1 - s2
  }

  def beforeInTextualOrder(m1: Mention, m2: Mention): Boolean = {
    val o = textualOrder(m1.phrase, m2.phrase)
    if (o == 0) textualOrder(m1.phrase, m2.phrase) < 0
    else o < 0
  }


  def printConll2011Format(doc: Document, map: GenericEntityMap[Mention], out: java.io.PrintStream) {
    val mappedMentions = map.entities.filterNot(_._2.size == 1).toSeq.flatMap(_._2).sortWith((s, t) => beforeInTextualOrder(s, t))
    val (singleTokMents, multiTokMents) = mappedMentions.partition(_.phrase.length == 1)
    val beginningTokMap = multiTokMents.groupBy(_.phrase.head)
    val endingTokMap = multiTokMents.groupBy(_.phrase.last)
    val singleTokMap = singleTokMents.groupBy(_.phrase.head)
    val fId = doc.name
    val docName = fId.substring(0, fId.length() - 4)
    val partNum = fId.takeRight(3)

    out.println("#begin document (" + docName + "); part " + partNum)
    for (s <- doc.sentences) {
      for (ti <- 0 until s.tokens.size) {
        val beginningMents = beginningTokMap.get(s(ti))
        val endingMents = endingTokMap.get(s(ti))
        val singleTokMents = singleTokMap.get(s(ti))
        assert(singleTokMents.size <= 1)
        out.print(docName + " " + partNum.toInt + " " + (ti + 1) + " " + s(ti).string + " " + s(ti).posTag.value + " - - - - - - - ")
        var ments = List[String]()
        if (beginningMents.isDefined) ments = beginningMents.get.reverse.map(m => "(" + map.reverseMap(m)).mkString("|") :: ments
        if (singleTokMents.isDefined) ments = singleTokMents.get.map(m => "(" + map.reverseMap(m) + ")").mkString("|") :: ments
        if (endingMents.isDefined) ments = endingMents.get.reverse.map(m => map.reverseMap(m) + ")").mkString("|") :: ments
        if (ments.size > 0) out.println(ments.mkString("|"))
        else out.println("-")
      }
      out.println()
    }
    out.println("#end document")
  }

  def printConll2011FormatNONFILTEREDMaybe(doc: Document, map: GenericEntityMap[Mention], out: java.io.PrintStream) {
    val mappedMentions = doc.attr[MentionList]
    val (singleTokMents, multiTokMents) = mappedMentions.partition(_.phrase.length == 1)
    val beginningTokMap = multiTokMents.groupBy(_.phrase.head)
    val endingTokMap = multiTokMents.groupBy(_.phrase.last)
    val singleTokMap = singleTokMents.groupBy(_.phrase.head)
    val fId = doc.name
    val docName = fId.substring(0, fId.length() - 4)
    val partNum = fId.takeRight(3)

    out.println("#begin document (" + docName + "); part " + partNum)
    for (s <- doc.sentences) {
      for (ti <- 0 until s.tokens.size) {
        val beginningMents = beginningTokMap.get(s(ti))
        val endingMents = endingTokMap.get(s(ti))
        val singleTokMents = singleTokMap.get(s(ti))
        assert(singleTokMents.size <= 1)
        out.print(docName + " " + partNum.toInt + " " + (ti + 1) + " " + s(ti).string + " " + s(ti).posTag.categoryValue + " - - - - - - - ")
        var ments = List[String]()
        if (beginningMents.isDefined) ments = beginningMents.get.reverse.map(m => "(" + map.reverseMap(m)).mkString("|") :: ments
        if (singleTokMents.isDefined) ments = singleTokMents.get.map(m => "(" + map.reverseMap(m) + ")").mkString("|") :: ments
        if (endingMents.isDefined) ments = endingMents.get.reverse.map(m => map.reverseMap(m) + ")").mkString("|") :: ments
        if (ments.size > 0) out.println(ments.mkString("|"))
        else out.println("-")
      }
      out.println()
    }
    out.println("#end document")
  }


  def printInhouseScore(name: String = "Test") {
    print("--- MACRO ---\n")
    print(name+" "+macroPW.toString("PW") + "\n")
    // print(macroB3.toString("B3") + "\n")
    print(name+" "+macroMUC.toString("MUC") + "\n")
    print(name+" macro "+macroBlanc.toString("BLANC") + "\n")
    print("--- MICRO ---\n")
    print(name+" micro "+microPW.toString("PW") + "\n")
    print(name+" micro "+microB3.toString("B3") + "\n")
    print(name+" micro "+microMUC.toString("MUC") + "\n")
    print(name+" micro "+microCE.toString("C-E") + "\n")
    print(name+" micro "+microCM.toString("C-M") + "\n")
  }
}

