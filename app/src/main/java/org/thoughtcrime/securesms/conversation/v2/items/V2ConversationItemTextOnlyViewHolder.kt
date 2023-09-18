/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.conversation.v2.items

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import org.signal.core.util.StringUtil
import org.signal.core.util.dp
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.mention.MentionAnnotation
import org.thoughtcrime.securesms.conversation.BodyBubbleLayoutTransition
import org.thoughtcrime.securesms.conversation.ConversationAdapterBridge
import org.thoughtcrime.securesms.conversation.ConversationItemDisplayMode
import org.thoughtcrime.securesms.conversation.ConversationMessage
import org.thoughtcrime.securesms.conversation.colors.ChatColors
import org.thoughtcrime.securesms.conversation.mutiselect.Multiselect
import org.thoughtcrime.securesms.conversation.mutiselect.MultiselectPart
import org.thoughtcrime.securesms.conversation.mutiselect.Multiselectable
import org.thoughtcrime.securesms.conversation.v2.data.ConversationMessageElement
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.InterceptableLongClickCopyLinkSpan
import org.thoughtcrime.securesms.util.LongClickMovementMethod
import org.thoughtcrime.securesms.util.PlaceholderURLSpan
import org.thoughtcrime.securesms.util.Projection
import org.thoughtcrime.securesms.util.ProjectionList
import org.thoughtcrime.securesms.util.SearchUtil
import org.thoughtcrime.securesms.util.SignalLocalMetrics
import org.thoughtcrime.securesms.util.ThemeUtil
import org.thoughtcrime.securesms.util.VibrateUtil
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel
import org.thoughtcrime.securesms.util.hasExtraText
import org.thoughtcrime.securesms.util.hasNoBubble
import org.thoughtcrime.securesms.util.isScheduled
import org.thoughtcrime.securesms.util.visible
import java.util.Locale

/**
 * Represents a text-only conversation item.
 */
open class V2ConversationItemTextOnlyViewHolder<Model : MappingModel<Model>>(
  private val binding: V2ConversationItemTextOnlyBindingBridge,
  private val conversationContext: V2ConversationContext,
  footerDelegate: V2FooterPositionDelegate = V2FooterPositionDelegate(binding)
) : V2ConversationItemViewHolder<Model>(binding.root, conversationContext), Multiselectable, InteractiveConversationElement {

  companion object {
    private val STYLE_FACTORY = SearchUtil.StyleFactory { arrayOf<CharacterStyle>(BackgroundColorSpan(Color.YELLOW), ForegroundColorSpan(Color.BLACK)) }
    private const val CONDENSED_MODE_MAX_LINES = 3

    private val footerCorners = Projection.Corners(18f.dp)
    private val transparentChatColors = ChatColors.forColor(ChatColors.Id.NotSet, Color.TRANSPARENT)
  }

  private var messageId: Long = Long.MAX_VALUE

  private val projections = ProjectionList()
  private val dispatchTouchEventListener = V2OnDispatchTouchEventListener(conversationContext, binding)

  override lateinit var conversationMessage: ConversationMessage

  override val root: ViewGroup = binding.root
  override val bubbleView: View = binding.bodyWrapper

  override val bubbleViews: List<View> = listOfNotNull(
    binding.bodyWrapper,
    binding.footerDate,
    binding.footerExpiry,
    binding.deliveryStatus,
    binding.footerBackground
  )

  override val reactionsView: View = binding.reactions
  override val quotedIndicatorView: View? = null
  override val replyView: View = binding.reply
  override val contactPhotoHolderView: View? = binding.senderPhoto
  override val badgeImageView: View? = binding.senderBadge

  private var reactionMeasureListener: ReactionMeasureListener = ReactionMeasureListener()

  private val bodyBubbleDrawable = ChatColorsDrawable()
  private val footerDrawable = ChatColorsDrawable()
  private val senderDrawable = ChatColorsDrawable()
  private val bodyBubbleLayoutTransition = BodyBubbleLayoutTransition()

  protected lateinit var shape: V2ConversationItemShape.MessageShape

  private val replyDelegate = object : V2ConversationItemLayout.OnMeasureListener {
    override fun onPreMeasure() = Unit

    override fun onPostMeasure(): Boolean {
      val wrapperHeight = binding.bodyWrapper.measuredHeight
      val yTranslation = (wrapperHeight - 38.dp) / 2f
      binding.reply.translationY = -yTranslation

      return false
    }
  }

  init {
    binding.root.addOnMeasureListener(footerDelegate)
    binding.root.addOnMeasureListener(replyDelegate)

    binding.root.onDispatchTouchEventListener = dispatchTouchEventListener

    binding.reactions.setOnClickListener {
      conversationContext.clickListener
        .onReactionClicked(
          Multiselect.getParts(conversationMessage).asSingle().singlePart,
          conversationMessage.messageRecord.id,
          conversationMessage.messageRecord.isMms
        )
    }

    binding.root.setOnClickListener {
      conversationContext.clickListener.onItemClick(getMultiselectPartForLatestTouch())
    }

    binding.root.setOnLongClickListener {
      conversationContext.clickListener.onItemLongClick(binding.root, getMultiselectPartForLatestTouch())

      true
    }

    val passthroughClickListener = PassthroughClickListener()
    binding.body.setOnClickListener(passthroughClickListener)
    binding.body.setOnLongClickListener(passthroughClickListener)

    binding.body.isFocusable = false
    binding.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, SignalStore.settings().messageFontSize.toFloat())
    binding.body.movementMethod = LongClickMovementMethod.getInstance(context)

    if (binding.isIncoming) {
      binding.body.setMentionBackgroundTint(ContextCompat.getColor(context, if (ThemeUtil.isDarkTheme(context)) R.color.core_grey_60 else R.color.core_grey_20))
    } else {
      binding.body.setMentionBackgroundTint(ContextCompat.getColor(context, R.color.transparent_black_25))
    }

    binding.bodyWrapper.background = bodyBubbleDrawable
    binding.bodyWrapper.layoutTransition = bodyBubbleLayoutTransition

    binding.footerBackground.background = footerDrawable
  }

  override fun invalidateChatColorsDrawable(coordinateRoot: ViewGroup) {
    invalidateBodyBubbleDrawable(coordinateRoot)
    invalidateFooterDrawable(coordinateRoot)
  }

  override fun bind(model: Model) {
    var hasProcessedSupportedPayload = false

    binding.bodyWrapper.layoutTransition = if (conversationContext.isParentInScroll) {
      null
    } else {
      bodyBubbleLayoutTransition
    }

    if (ConversationAdapterBridge.PAYLOAD_PARENT_SCROLLING in payload) {
      if (payload.size == 1) {
        return
      } else {
        hasProcessedSupportedPayload = true
      }
    }

    check(model is ConversationMessageElement)
    conversationMessage = model.conversationMessage

    shape = shapeDelegate.setMessageShape(
      isLtr = itemView.layoutDirection == View.LAYOUT_DIRECTION_LTR,
      currentMessage = conversationMessage.messageRecord,
      isGroupThread = conversationMessage.threadRecipient.isGroup,
      adapterPosition = bindingAdapterPosition
    )

    if (ConversationAdapterBridge.PAYLOAD_TIMESTAMP in payload) {
      presentDate()
      hasProcessedSupportedPayload = true
    }

    if (ConversationAdapterBridge.PAYLOAD_NAME_COLORS in payload) {
      presentSenderNameColor()
      hasProcessedSupportedPayload = true
    }

    if (V2Payload.SEARCH_QUERY_UPDATED in payload) {
      presentBody()
      hasProcessedSupportedPayload = true
    }

    if (hasProcessedSupportedPayload) {
      return
    }

    presentBody()
    presentDate()
    presentDeliveryStatus()
    presentFooterBackground()
    presentFooterExpiry()
    presentAlert()
    presentSender()
    presentSenderNameColor()
    presentSenderNameBackground()
    presentReactions()

    bodyBubbleDrawable.setChatColors(
      if (binding.body.isJumbomoji) {
        transparentChatColors
      } else if (binding.isIncoming) {
        ChatColors.forColor(ChatColors.Id.NotSet, themeDelegate.getBodyBubbleColor(conversationMessage))
      } else {
        conversationMessage.threadRecipient.chatColors
      },
      shapeDelegate.corners
    )

    binding.reply.setBackgroundColor(themeDelegate.getReplyIconBackgroundColor())

    itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
      topMargin = shape.topPadding.toInt()
      bottomMargin = shape.bottomPadding.toInt()
    }
  }

  override fun getAdapterPosition(recyclerView: RecyclerView): Int = bindingAdapterPosition

  override fun getSnapshotProjections(coordinateRoot: ViewGroup, clipOutMedia: Boolean): ProjectionList {
    return getSnapshotProjections(coordinateRoot, clipOutMedia, true)
  }

  override fun getSnapshotProjections(coordinateRoot: ViewGroup, clipOutMedia: Boolean, outgoingOnly: Boolean): ProjectionList {
    projections.clear()

    if (outgoingOnly && binding.isIncoming) {
      return projections
    }

    projections.add(
      Projection.relativeToParent(
        coordinateRoot,
        binding.bodyWrapper,
        shapeDelegate.corners
      ).translateX(binding.bodyWrapper.translationX).translateY(root.translationY)
    )

    return projections
  }

  override fun getSnapshotStrategy(): InteractiveConversationElement.SnapshotStrategy {
    return V2ConversationItemSnapshotStrategy(binding)
  }

  /**
   * Note: This is not necessary for CFV2 Text-Only items because the background is rendered by
   * [ChatColorsDrawable]
   */
  override fun getColorizerProjections(coordinateRoot: ViewGroup): ProjectionList {
    projections.clear()

    return projections
  }

  override fun getTopBoundaryOfMultiselectPart(multiselectPart: MultiselectPart): Int {
    return root.top
  }

  override fun getBottomBoundaryOfMultiselectPart(multiselectPart: MultiselectPart): Int {
    return root.bottom
  }

  override fun getMultiselectPartForLatestTouch(): MultiselectPart {
    return conversationMessage.multiselectCollection.asSingle().singlePart
  }

  override fun getHorizontalTranslationTarget(): View? {
    return if (conversationMessage.messageRecord.isOutgoing) {
      null
    } else if (conversationMessage.threadRecipient.isGroup) {
      binding.senderPhoto
    } else {
      binding.bodyWrapper
    }
  }

  override fun hasNonSelectableMedia(): Boolean = false
  override fun showProjectionArea() = Unit

  override fun hideProjectionArea() = Unit

  override fun getGiphyMp4PlayableProjection(coordinateRoot: ViewGroup): Projection {
    return Projection.relativeToParent(
      coordinateRoot,
      binding.bodyWrapper,
      shapeDelegate.corners
    )
      .translateY(root.translationY)
      .translateX(binding.bodyWrapper.translationX)
      .translateX(root.translationX)
  }

  override fun canPlayContent(): Boolean = false

  override fun shouldProjectContent(): Boolean = false

  private fun invalidateFooterDrawable(coordinateRoot: ViewGroup) {
    if (footerDrawable.isSolidColor()) {
      return
    }

    val projection = Projection.relativeToParent(
      coordinateRoot,
      binding.footerBackground,
      shapeDelegate.corners
    )

    footerDrawable.applyMaskProjection(projection)
    projection.release()
  }

  private fun invalidateBodyBubbleDrawable(coordinateRoot: ViewGroup) {
    if (bodyBubbleDrawable.isSolidColor()) {
      return
    }

    val projection = Projection.relativeToParent(
      coordinateRoot,
      binding.bodyWrapper,
      shapeDelegate.corners
    )

    bodyBubbleDrawable.applyMaskProjection(projection)
    projection.release()
  }

  private fun MessageRecord.buildMessageId(): Long {
    return if (isMms) -id else id
  }

  private fun presentBody() {
    binding.body.setTextColor(themeDelegate.getBodyTextColor(conversationMessage))
    binding.body.setLinkTextColor(themeDelegate.getBodyTextColor(conversationMessage))

    val record = conversationMessage.messageRecord
    var styledText: Spannable = conversationMessage.getDisplayBody(context)
    if (conversationContext.isMessageRequestAccepted) {
      linkifyMessageBody(styledText)
    }

    styledText = SearchUtil.getHighlightedSpan(Locale.getDefault(), STYLE_FACTORY, styledText, conversationContext.searchQuery, SearchUtil.STRICT)
    if (record.hasExtraText()) {
      binding.body.setOverflowText(getLongMessageSpan())
    } else {
      binding.body.setOverflowText(null)
    }

    if (isContentCondensed()) {
      binding.body.maxLines = CONDENSED_MODE_MAX_LINES
    } else {
      binding.body.maxLines = Integer.MAX_VALUE
    }

    val bodyText = StringUtil.trim(styledText)

    binding.body.visible = bodyText.isNotEmpty()
    binding.body.text = bodyText
  }

  private fun linkifyMessageBody(messageBody: Spannable) {
    V2ConversationItemUtils.linkifyUrlLinks(messageBody, conversationContext.selectedItems.isEmpty(), conversationContext.clickListener::onUrlClicked)

    if (conversationMessage.hasStyleLinks()) {
      messageBody.getSpans(0, messageBody.length, PlaceholderURLSpan::class.java).forEach { placeholder ->
        val start = messageBody.getSpanStart(placeholder)
        val end = messageBody.getSpanEnd(placeholder)
        val span: URLSpan = InterceptableLongClickCopyLinkSpan(
          placeholder.value,
          conversationContext.clickListener::onUrlClicked,
          ContextCompat.getColor(getContext(), R.color.signal_accent_primary),
          false
        )

        messageBody.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }

    MentionAnnotation.getMentionAnnotations(messageBody).forEach { annotation ->
      messageBody.setSpan(
        MentionClickableSpan(RecipientId.from(annotation.value)),
        messageBody.getSpanStart(annotation),
        messageBody.getSpanEnd(annotation),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
      )
    }
  }

  private fun getLongMessageSpan(): CharSequence {
    val message = context.getString(R.string.ConversationItem_read_more)
    val span = SpannableStringBuilder(message)

    span.setSpan(ReadMoreSpan(), 0, span.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    return span
  }

  private inner class MentionClickableSpan(
    private val recipientId: RecipientId
  ) : ClickableSpan() {
    override fun onClick(widget: View) {
      VibrateUtil.vibrateTick(context)
      conversationContext.clickListener.onGroupMemberClicked(recipientId, conversationMessage.threadRecipient.requireGroupId())
    }

    override fun updateDrawState(ds: TextPaint) = Unit
  }

  private inner class ReadMoreSpan : ClickableSpan() {
    override fun onClick(widget: View) {
      if (conversationContext.selectedItems.isEmpty()) {
        conversationContext.clickListener.onMoreTextClicked(
          conversationMessage.threadRecipient.id,
          conversationMessage.messageRecord.id,
          conversationMessage.messageRecord.isMms
        )
      }
    }

    override fun updateDrawState(ds: TextPaint) {
      ds.typeface = Typeface.DEFAULT_BOLD
    }
  }

  private fun isContentCondensed(): Boolean {
    return conversationContext.displayMode is ConversationItemDisplayMode.Condensed && conversationContext.getPreviousMessage(bindingAdapterPosition) == null
  }

  private fun presentFooterExpiry() {
    if (shape == V2ConversationItemShape.MessageShape.MIDDLE || shape == V2ConversationItemShape.MessageShape.START) {
      binding.footerExpiry.stopAnimation()
      binding.footerExpiry.visible = false
      return
    }

    binding.footerExpiry.setColorFilter(themeDelegate.getFooterIconColor(conversationMessage))

    val timer = binding.footerExpiry
    val record = conversationMessage.messageRecord
    if (record.expiresIn > 0 && !record.isPending) {
      timer.visible = true
      timer.setPercentComplete(0f)

      if (record.expireStarted > 0) {
        timer.setExpirationTime(record.expireStarted, record.expiresIn)
        timer.startAnimation()

        if (record.expireStarted + record.expiresIn <= System.currentTimeMillis()) {
          ApplicationDependencies.getExpiringMessageManager().checkSchedule()
        }
      } else if (!record.isOutgoing && !record.isMediaPending) {
        conversationContext.onStartExpirationTimeout(record)
      }
    } else {
      timer.visible = false
    }
  }

  private fun presentSenderNameBackground() {
    if (binding.senderName == null || !shape.isStartingShape || !conversationMessage.threadRecipient.isGroup || !conversationMessage.messageRecord.hasNoBubble(context)) {
      return
    }

    if (conversationContext.hasWallpaper()) {
      senderDrawable.setChatColors(
        ChatColors.forColor(ChatColors.Id.BuiltIn, themeDelegate.getFooterBubbleColor(conversationMessage)),
        footerCorners
      )

      binding.senderName.background = senderDrawable
    } else {
      binding.senderName.background = null
    }
  }

  private fun presentSender() {
    if (binding.senderName == null || binding.senderPhoto == null || binding.senderBadge == null) {
      return
    }

    if (conversationMessage.threadRecipient.isGroup) {
      val sender = conversationMessage.messageRecord.fromRecipient

      binding.senderPhoto.visibility = if (shape.isEndingShape) {
        View.VISIBLE
      } else {
        View.INVISIBLE
      }

      binding.senderName.visible = shape.isStartingShape
      binding.senderBadge.visible = shape.isEndingShape

      binding.senderName.text = sender.getDisplayName(context)
      binding.senderPhoto.setAvatar(conversationContext.glideRequests, sender, false)
      binding.senderBadge.setBadgeFromRecipient(sender, conversationContext.glideRequests)
      binding.senderPhoto.setOnClickListener {
        conversationContext.clickListener.onGroupMemberClicked(
          conversationMessage.messageRecord.fromRecipient.id,
          conversationMessage.threadRecipient.requireGroupId()
        )
      }
    } else {
      binding.senderName.visible = false
      binding.senderPhoto.visible = false
      binding.senderBadge.visible = false
    }
  }

  private fun presentSenderNameColor() {
    if (binding.senderName == null || !conversationMessage.threadRecipient.isGroup) {
      return
    }

    val sender = conversationMessage.messageRecord.fromRecipient
    binding.senderName.setTextColor(conversationContext.getColorizer().getIncomingGroupSenderColor(context, sender))
  }

  private fun presentAlert() {
    val record = conversationMessage.messageRecord
    binding.body.setCompoundDrawablesWithIntrinsicBounds(
      0,
      0,
      if (record.isKeyExchange) R.drawable.ic_menu_login else 0,
      0
    )

    val alert = binding.alert ?: return

    when {
      record.isFailed -> alert.setFailed()
      record.isPendingInsecureSmsFallback -> alert.setPendingApproval()
      record.isRateLimited -> alert.setRateLimited()
      else -> alert.setNone()
    }

    if (conversationContext.hasWallpaper()) {
      alert.setBackgroundResource(R.drawable.wallpaper_message_decoration_background)
    } else {
      alert.background = null
    }
  }

  private fun presentReactions() {
    if (conversationMessage.messageRecord.reactions.isEmpty()) {
      binding.reactions.clear()
      binding.root.removeOnMeasureListener(reactionMeasureListener)
    } else {
      binding.reactions.setReactions(conversationMessage.messageRecord.reactions)
      binding.root.addOnMeasureListener(reactionMeasureListener)
    }
  }

  private fun presentFooterBackground() {
    if (!binding.body.isJumbomoji ||
      !conversationContext.hasWallpaper() ||
      shape == V2ConversationItemShape.MessageShape.MIDDLE ||
      shape == V2ConversationItemShape.MessageShape.START
    ) {
      binding.footerBackground.visible = false
      return
    }

    binding.footerBackground.visible = true
    footerDrawable.setChatColors(
      if (binding.isIncoming) {
        ChatColors.forColor(ChatColors.Id.NotSet, themeDelegate.getFooterBubbleColor(conversationMessage))
      } else {
        conversationMessage.threadRecipient.chatColors
      },
      footerCorners
    )
  }

  private fun presentDate() {
    if (shape == V2ConversationItemShape.MessageShape.MIDDLE || shape == V2ConversationItemShape.MessageShape.START) {
      binding.footerDate.visible = false
      return
    }

    binding.footerDate.setOnClickListener(null)
    binding.footerDate.visible = true
    binding.footerDate.setTextColor(themeDelegate.getFooterTextColor(conversationMessage))

    val record = conversationMessage.messageRecord
    if (record.isFailed) {
      val errorMessage = when {
        record.hasFailedWithNetworkFailures() -> R.string.ConversationItem_error_network_not_delivered
        record.toRecipient.isPushGroup && record.isIdentityMismatchFailure -> R.string.ConversationItem_error_partially_not_delivered
        else -> R.string.ConversationItem_error_not_sent_tap_for_details
      }

      binding.footerDate.setText(errorMessage)
    } else if (record.isPendingInsecureSmsFallback) {
      binding.footerDate.setText(R.string.ConversationItem_click_to_approve_unencrypted)
    } else if (record.isRateLimited) {
      binding.footerDate.setText(R.string.ConversationItem_send_paused)
    } else if (record.isScheduled()) {
      binding.footerDate.text = conversationMessage.formattedDate
    } else {
      var date = conversationMessage.formattedDate
      if (conversationContext.displayMode != ConversationItemDisplayMode.Detailed && record is MediaMmsMessageRecord && record.isEditMessage()) {
        date = getContext().getString(R.string.ConversationItem_edited_timestamp_footer, date)

        binding.footerDate.setOnClickListener {
          conversationContext.clickListener.onEditedIndicatorClicked(record)
        }
      }

      binding.footerDate.text = date
    }
  }

  private fun presentDeliveryStatus() {
    val deliveryStatus = binding.deliveryStatus ?: return

    if (shape == V2ConversationItemShape.MessageShape.MIDDLE || shape == V2ConversationItemShape.MessageShape.START) {
      deliveryStatus.setNone()
      return
    }

    val record = conversationMessage.messageRecord
    val newMessageId = record.buildMessageId()

    if (messageId != newMessageId && deliveryStatus.isPending && !record.isPending) {
      if (record.toRecipient.isGroup) {
        SignalLocalMetrics.GroupMessageSend.onUiUpdated(record.id)
      } else {
        SignalLocalMetrics.IndividualMessageSend.onUiUpdated(record.id)
      }
    }

    messageId = newMessageId

    if (!record.isOutgoing || record.isFailed || record.isPendingInsecureSmsFallback || record.isScheduled()) {
      deliveryStatus.setNone()
      return
    }

    deliveryStatus.setTint(themeDelegate.getFooterIconColor(conversationMessage))

    val onlyShowSendingStatus = when {
      record.isOutgoing && !record.isRemoteDelete -> false
      record.isRemoteDelete -> true
      else -> false
    }

    if (onlyShowSendingStatus) {
      if (record.isPending) {
        deliveryStatus.setPending()
      } else {
        deliveryStatus.setNone()
      }

      return
    }

    when {
      record.isPending -> deliveryStatus.setPending()
      record.isRemoteRead -> deliveryStatus.setRead()
      record.isDelivered -> deliveryStatus.setDelivered()
      else -> deliveryStatus.setSent()
    }
  }

  override fun disallowSwipe(latestDownX: Float, latestDownY: Float): Boolean {
    return false
  }

  private inner class ReactionMeasureListener : V2ConversationItemLayout.OnMeasureListener {
    override fun onPreMeasure() = Unit

    override fun onPostMeasure(): Boolean {
      return binding.reactions.setBubbleWidth(binding.bodyWrapper.measuredWidth)
    }
  }

  private inner class PassthroughClickListener : View.OnClickListener, View.OnLongClickListener {
    override fun onClick(v: View?) {
      binding.root.performClick()
    }

    override fun onLongClick(v: View?): Boolean {
      if (binding.body.hasSelection()) {
        return false
      }

      binding.root.performLongClick()
      return true
    }
  }
}
