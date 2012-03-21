/** 
Open Bank Project

Copyright 2011,2012 TESOBE / Music Pictures Ltd.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and 
limitations under the License. 

Open Bank Project (http://www.openbankproject.com)
      Copyright 2011,2012 TESOBE / Music Pictures Ltd

      This product includes software developed at
      TESOBE (http://www.tesobe.com/)
		by 
		Simon Redfern : simon AT tesobe DOT com
		Everett Sochowski: everett AT tesobe DOT com

 */
package code.snippet

import net.liftweb.http.{PaginatorSnippet, StatefulSnippet}
import java.text.SimpleDateFormat
import net.liftweb.http._
import java.util.Calendar
import code.model.OBPTransaction
import code.model.OBPEnvelope
import xml.NodeSeq
import com.mongodb.QueryBuilder
import net.liftweb.mongodb.Limit._
import net.liftweb.mongodb.Skip._
import net.liftweb.util.Helpers._
import net.liftweb.util._
import scala.xml.Text
import net.liftweb.common.{Box, Failure, Empty, Full}
import java.util.Date
import code.model.OBPAccount
import code.model.OBPAccount.{APublicAlias, APrivateAlias}
import net.liftweb.http.js.JsCmds.Noop

class OBPTransactionSnippet extends StatefulSnippet with PaginatorSnippet[OBPEnvelope] {

  override def count = OBPEnvelope.count


  override def itemsPerPage = 5
  //override def page = OBPTransaction.findAll(QueryBuilder.start().get(), Limit(itemsPerPage), Skip(curPage*itemsPerPage))
  override def page : List[OBPEnvelope]= {
      // TODO we need to get Rogue going otherwise its possible to write queries that don't make sense!
      // val qry = QueryBuilder.start("obp_transaction_data_blob").notEquals("simon-says").get

      val qry = QueryBuilder.start().get
      var obp_envelopes = OBPEnvelope.findAll(qry)

      obp_envelopes
  }

  var dispatch: DispatchIt = {
    case "showAll" => showAll _
    case "paginate" => paginate _
    case "display" => display _
    //case "top" => top _
  }
  
  def display(xhtml: NodeSeq) : NodeSeq = {
    val FORBIDDEN = "---"
    val consumer = S.uri match{
      case uri if uri.endsWith("authorities") => "authorities"
      case uri if uri.endsWith("board") => "board"
      case uri if uri.endsWith("our-network") => "our-network"
      case uri if uri.endsWith("team") => "team"
      case uri if uri.endsWith("my-view") => "my-view"
      case _ => "anonymous"
    }
    
    def orderByDateDescending = (e1: OBPEnvelope, e2: OBPEnvelope) => {
     val date1 = e1.obp_transaction.get.details.get.mediated_completed(consumer) getOrElse new Date()
     val date2 = e2.obp_transaction.get.details.get.mediated_completed(consumer) getOrElse new Date()
     date1.after(date2)
   } 
   
   def hasSameDate(e1: OBPEnvelope, e2: OBPEnvelope) : Boolean = {
    val t1 = e1.obp_transaction.get
    val t2 = e2.obp_transaction.get
    
    t1.details.get.completed.get.equals(t2.details.get.completed.get)
  }
    
   /**
    * Splits a list of envelopes into a list of lists, where each of these new lists
    * is for one day.
    * 
    * Example:
    * 	input : List(Jan 5,Jan 6,Jan 7,Jan 7,Jan 8,Jan 9,Jan 9,Jan 9,Jan 10)
    * 	output: List(List(Jan 5), List(Jan 6), List(Jan 7,Jan 7), 
    * 				 List(Jan 8), List(Jan 9,Jan 9,Jan 9), List(Jan 10))
    */
   def groupByDate(list : List[OBPEnvelope]) : List[List[OBPEnvelope]] = {
        list match{
          case Nil => Nil
          case h::Nil => List(list)
          case h::t => {
            //transactions that are identical to the head of the list
            val matches = list.filter(hasSameDate(h, _))
            List(matches) ++ groupByDate(list diff matches)
          }
        }
   }
   
   
    
   val envelopes = groupByDate(page.sort(orderByDateDescending))
   
   val dateFormat = new SimpleDateFormat("MMMM dd, yyyy")
   
   def formatDate(date : Box[Date]) : String = {
        date match{
          case Full(d) => dateFormat.format(d)
          case _ => FORBIDDEN
        }
   }
   
   ("* *" #> envelopes.map( envsForDay => {
     val dailyDetails = envsForDay.last.obp_transaction.get.details.get
     val date = formatDate(dailyDetails.mediated_completed(consumer))
     //TODO: This isn't really going to be the right balance, as there's no way of telling which one was the actual
     // last transaction of the day yet
     val balance = dailyDetails.new_balance.get.mediated_amount(consumer) getOrElse FORBIDDEN
     ".date *" #> date &
     ".balance_number *" #> {"€" + balance} & //TODO: support other currencies, format the balance according to locale
     ".transaction_row *" #> envsForDay.map(env =>{
       
       val envelopeID = env.id
      
       val transaction = env.obp_transaction.get
       val transactionDetails = transaction.details.get
       val transactionValue = transactionDetails.value.get
       val thisAccount = transaction.this_account.get
       val otherAccount  = transaction.other_account.get
      
       var narrative = env.narrative.get
      
       def editableNarrative() = {
    	  SHtml.ajaxEditable(Text(narrative), SHtml.text(narrative, narrative = _), ()=> {
    	    //save the narrative
    	    env.narrative(narrative).save
    	    Noop
    	  })
       }
      
       def displayNarrative() : NodeSeq = {
         consumer match{
           case "my-view" => editableNarrative()
           case _ => Text(env.mediated_narrative(consumer).getOrElse(FORBIDDEN))
         }
       }
      
       val theAccount = thisAccount.theAccount
       val otherUnmediatedHolder = otherAccount.holder.get
       val otherMediatedHolder = otherAccount.mediated_holder(consumer)
      
       val aliasImageSrc = {
         otherMediatedHolder._2 match{
           case Full(APublicAlias) => "/media/images/public_alias.png"
           case Full(APrivateAlias) => "/media/images/private_alias.png"
           case _ => ""
         }
       }
      
       val moreInfo = {
         val moreInfo = for{
           a <- theAccount
           oacc <- a.otherAccounts.get.find(o => otherUnmediatedHolder.equals(o.holder.get))
         } yield oacc.moreInfo.get
        
         moreInfo getOrElse ""
       }
      
       val logoImageSrc = {
          val imageUrl = for{
           a <- theAccount
           oacc <- a.otherAccounts.get.find(o => otherUnmediatedHolder.equals(o.holder.get))
         } yield oacc.imageUrl.get
        
        imageUrl getOrElse ""
       }
      
       val otherAccWebsiteUrl = {
         val url = for{
           a <- theAccount
           oacc <- a.otherAccounts.get.find(o => otherUnmediatedHolder.equals(o.holder.get))
         } yield oacc.url.get
        
         url getOrElse ""
       }
       val openCorporatesUrl = {
         val theUrl = for{
         	a <- theAccount
            oacc <- a.otherAccounts.get.find(o => otherUnmediatedHolder.equals(o.holder.get))
         } yield oacc.openCorporatesUrl.get
         theUrl getOrElse("")
       }
       
       val amount = transactionValue.mediated_amount(consumer).getOrElse(FORBIDDEN)
       val name = otherMediatedHolder._1.getOrElse(FORBIDDEN)
       
       (".the_name *" #> name &
        ".amount *" #> {"€" + amount.stripPrefix("-")} & //TODO: Format this number according to locale
        {
        if(aliasImageSrc.equals("")){
          ".alias_image" #> NodeSeq.Empty & //remove the img tag
          ".alias_divider" #> NodeSeq.Empty //remove the divider (br tag)
        } 
        else ".alias_image [src]" #> {aliasImageSrc}
        } &
        {
         if(aliasImageSrc.equals("media/images/public_alias.png")){ 
           //don't show more info if there is a public alias
           ".extra *" #> NodeSeq.Empty
         } else {
           //show it otherwise
           ".extra *" #> moreInfo
         }
        } &
        ".comments_ext [href]" #> {consumer + "/transactions/" + envelopeID + "/comments"} &
        ".comment *" #> env.mediated_comments(consumer).getOrElse(Nil).size &
        ".symbol *" #> {if(amount.startsWith("-")) "-" else "+"} &
        ".out [class]" #> {if(amount.startsWith("-")) "out" else "in"})
       
     })
   } 
   )).apply(xhtml)
   
   
  }
  
  def showAll(xhtml: NodeSeq): NodeSeq = {

    val consumer = S.uri match{
      case uri if uri.endsWith("authorities") => "authorities"
      case uri if uri.endsWith("board") => "board"
      case uri if uri.endsWith("our-network") => "our-network"
      case uri if uri.endsWith("team") => "team"
      case uri if uri.endsWith("my-view") => "my-view"
      case _ => "anonymous"
    }
    
   def orderByDateDescending = (e1: OBPEnvelope, e2: OBPEnvelope) => {
     val date1 = e1.obp_transaction.get.details.get.mediated_completed(consumer) getOrElse new Date()
     val date2 = e2.obp_transaction.get.details.get.mediated_completed(consumer) getOrElse new Date()
     date1.after(date2)
   } 
    
   val envelopes = page.sort(orderByDateDescending)
    
   envelopes.flatMap(obpEnvelope => {
      val FORBIDDEN = "---"
      
      val dateFormat = new SimpleDateFormat("MMM dd yyyy")
      
      val envelopeID = obpEnvelope.id
      
      val transaction = obpEnvelope.obp_transaction.get
      val transactionDetails = transaction.details.get
      val transactionValue = transactionDetails.value.get
      val thisAccount = transaction.this_account.get
      val otherAccount  = transaction.other_account.get
      
      def formatDate(date : Box[Date]) : String = {
        date match{
          case Full(d) => dateFormat.format(d)
          case _ => FORBIDDEN
        }
      }
      
      var narrative = obpEnvelope.narrative.get
      
      def editableNarrative() = {
    	 SHtml.ajaxEditable(Text(narrative), SHtml.text(narrative, narrative = _), ()=> {
    	   //save the narrative
    	   obpEnvelope.narrative(narrative).save
    	   Noop
    	 })
      }
      
      def displayNarrative() : NodeSeq = {
        consumer match{
          case "my-view" => editableNarrative()
          case _ => Text(obpEnvelope.mediated_narrative(consumer).getOrElse(FORBIDDEN))
        }
      }
      
      val theAccount = thisAccount.theAccount
      val otherUnmediatedHolder = otherAccount.holder.get
      val otherMediatedHolder = otherAccount.mediated_holder(consumer)
      
      
      val aliasImageSrc = {
        otherMediatedHolder._2 match{
          case Full(APublicAlias) => "/images/public_alias.png"
          case Full(APrivateAlias) => "/images/private_alias.png"
          case _ => ""
        }
      }
      
      val moreInfo = {
        val moreInfo = for{
          a <- theAccount
          oacc <- a.otherAccounts.get.find(o => otherUnmediatedHolder.equals(o.holder.get))
        } yield oacc.moreInfo.get
        
        moreInfo getOrElse ""
      }
      
      val logoImageSrc = {
         val imageUrl = for{
          a <- theAccount
          oacc <- a.otherAccounts.get.find(o => otherUnmediatedHolder.equals(o.holder.get))
        } yield oacc.imageUrl.get
        
       imageUrl getOrElse ""
      }
      
      val otherAccWebsiteUrl = {
        val url = for{
          a <- theAccount
          oacc <- a.otherAccounts.get.find(o => otherUnmediatedHolder.equals(o.holder.get))
        } yield oacc.url.get
        
        url getOrElse ""
      }
      val openCorporatesUrl = {
        val theUrl = for{
        	a <- theAccount
            oacc <- a.otherAccounts.get.find(o => otherUnmediatedHolder.equals(o.holder.get))
        } yield oacc.openCorporatesUrl.get
        theUrl getOrElse("")
      }
      
      (
      ".amount *" #> transactionValue.mediated_amount(consumer).getOrElse(FORBIDDEN) &
      ".other_account_holder_name *" #> otherMediatedHolder._1.getOrElse(FORBIDDEN) &
      {
        if(aliasImageSrc.equals("")){
          ".alias_image" #> NodeSeq.Empty & //remove the img tag
          ".alias_divider" #> NodeSeq.Empty //remove the divider (br tag)
        } 
        else ".alias_image [src]" #> aliasImageSrc
      } &
      {
        //TODO: This was hacked minutes before for a demo. Needs to be redone.
        if(aliasImageSrc.equals("/images/public_alias.png")){ 
          //don't show more info if there is a public alias
          ".other_account_more_info *" #> NodeSeq.Empty &
          ".other_account_logo_img" #> NodeSeq.Empty &
          ".other_acc_link" #> NodeSeq.Empty &
          ".open_corporates_link" #> NodeSeq.Empty
        }else{
          //show it otherwise
          ".other_account_more_info *" #> moreInfo &
          ".other_account_logo_img [src]" #> logoImageSrc &
          {
        	 if(otherAccWebsiteUrl.equals("")) ".other_acc_link" #> NodeSeq.Empty //If there is no link to display, don't render the <a> element
        	 else".other_acc_link [href]" #> otherAccWebsiteUrl
          } &
          {
            if(openCorporatesUrl.equals("")) ".open_corporates_link" #> NodeSeq.Empty
        	else ".open_corporates_link [href]" #> openCorporatesUrl
          }
        }
      } &
      ".currency *" #> transactionValue.mediated_currency(consumer).getOrElse(FORBIDDEN) &
      ".date_cleared *" #> formatDate(transactionDetails.mediated_posted(consumer))&
      ".narrative *" #> displayNarrative &
      ".new_balance *" #> {
        transactionDetails.new_balance.get.mediated_amount(consumer).getOrElse(FORBIDDEN) + " " +
        transactionDetails.new_balance.get.mediated_currency(consumer).getOrElse(FORBIDDEN)} &
      ".comments_ext [href]" #> {consumer + "/transactions/" + envelopeID + "/comments"} &
      ".comments_title *" #> {"Comments (" + (obpEnvelope.mediated_comments(consumer) getOrElse List()).size + ")"}
      ).apply(xhtml)
      
    })
  }

}

