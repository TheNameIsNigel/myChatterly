package me.saket.dank.notifs;

import static me.saket.dank.utils.RxUtils.doNothingCompletable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.CheckResult;
import androidx.core.app.RemoteInput;
import android.widget.Toast;

import net.dean.jraw.models.Message;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

import dagger.Lazy;
import io.reactivex.Completable;
import io.reactivex.Single;
import me.saket.dank.R;
import me.saket.dank.data.MoshiAdapter;
import me.saket.dank.di.Dank;
import me.saket.dank.ui.UrlRouter;
import me.saket.dank.ui.submission.SubmissionPageLayoutActivity;
import me.saket.dank.ui.user.UserSessionRepository;
import me.saket.dank.ui.user.messages.InboxActivity;
import me.saket.dank.ui.user.messages.InboxFolder;
import me.saket.dank.ui.user.messages.PrivateMessageThreadActivity;
import me.saket.dank.urlparser.RedditSubmissionLink;
import me.saket.dank.urlparser.UrlParser;
import me.saket.dank.utils.JrawUtils2;
import timber.log.Timber;

/**
 * Receives actions made on unread message notifications, generated by {@link MessagesNotificationManager}
 * and delegates them to {@link MessageNotifActionsJobService},
 */
public class MessageNotifActionReceiver extends BroadcastReceiver {

  public static final String KEY_DIRECT_REPLY_MESSAGE = "me.saket.dank.directReplyMessage";
  private static final String INTENT_KEY_MESSAGE_JSON = "me.saket.dank.message";
  private static final String INTENT_KEY_MESSAGE_ID_LIST = "me.saket.dank.messageIdList";
  private static final String INTENT_KEY_MESSAGE_ARRAY_JSON = "me.saket.dank.messageArrayJson";

  @Inject Lazy<UserSessionRepository> userSessionRepository;
  @Inject Lazy<UrlParser> urlParser;
  @Inject Lazy<UrlRouter> urlRouter;
  @Inject Lazy<MoshiAdapter> moshiAdapter;

  public enum NotificationAction {
    DIRECT_REPLY,
    MARK_AS_READ,
    MARK_ALL_AS_READ,
    MARK_AS_SEEN,
    MARK_AS_SEEN_AND_OPEN_INBOX,
    MARK_AS_SEEN_AND_OPEN_MESSAGE,
  }

  @CheckResult
  public static Intent createDirectReplyIntent(Context context, Message replyToMessage, MoshiAdapter moshiAdapter, int notificationId) {
    if (notificationId == -1) {
      throw new AssertionError();
    }

    Intent intent = new Intent(context, MessageNotifActionReceiver.class);
    intent.setAction(NotificationAction.DIRECT_REPLY.name());
    intent.putExtra(INTENT_KEY_MESSAGE_JSON, moshiAdapter.create(Message.class).toJson(replyToMessage));
    return intent;
  }

  @CheckResult
  public static Intent createMarkAsReadIntent(Context context, MoshiAdapter moshiAdapter, Message... messages) {
    if (messages.length == 0) {
      throw new AssertionError();
    }

    Intent intent = new Intent(context, MessageNotifActionReceiver.class);
    intent.setAction(NotificationAction.MARK_AS_READ.name());
    intent.putExtra(INTENT_KEY_MESSAGE_ARRAY_JSON, moshiAdapter.create(Message[].class).toJson(messages));
    return intent;
  }

  /**
   * @param messagesToMarkAsRead Used for marking their notifications as "seen".
   */
  @CheckResult
  public static Intent createMarkAllAsReadIntent(Context context, List<Message> messagesToMarkAsRead) {
    ArrayList<String> messageIdsToMarkAsRead = new ArrayList<>(messagesToMarkAsRead.size());
    for (Message message : messagesToMarkAsRead) {
      messageIdsToMarkAsRead.add(message.getId());
    }

    // Don't need to store the message objects because marking all as read doesn't require any Message param.

    Intent intent = new Intent(context, MessageNotifActionReceiver.class);
    intent.setAction(NotificationAction.MARK_ALL_AS_READ.name());
    intent.putStringArrayListExtra(INTENT_KEY_MESSAGE_ID_LIST, messageIdsToMarkAsRead);
    return intent;
  }

  /**
   * Gets called when an individual notification is dismissed.
   */
  @CheckResult
  public static Intent createMarkAsSeenIntent(Context context, Message messageToMarkAsSeen) {
    ArrayList<Message> singleList = new ArrayList<>(1);
    singleList.add(messageToMarkAsSeen);
    return createMarkAllAsSeenIntent(context, singleList);
  }

  /**
   * Gets called when the entire bundled notification is dismissed.
   */
  @CheckResult
  public static Intent createMarkAllAsSeenIntent(Context context, List<Message> messagesToMarkAsSeen) {
    ArrayList<String> messageIdsToMarkAsSeen = new ArrayList<>(messagesToMarkAsSeen.size());
    for (Message message : messagesToMarkAsSeen) {
      messageIdsToMarkAsSeen.add(message.getId());
    }

    Intent intent = new Intent(context, MessageNotifActionReceiver.class);
    intent.setAction(NotificationAction.MARK_AS_SEEN.name());
    intent.putStringArrayListExtra(INTENT_KEY_MESSAGE_ID_LIST, messageIdsToMarkAsSeen);
    return intent;
  }

  /**
   * Mark all unread messages as seen and then open {@link InboxActivity}.
   */
  @CheckResult
  public static Intent createMarkSeenAndOpenInboxIntent(Context context, List<Message> messagesToMarkAsSeen) {
    ArrayList<String> messageIdsToMarkAsSeen = new ArrayList<>(messagesToMarkAsSeen.size());
    for (Message message : messagesToMarkAsSeen) {
      messageIdsToMarkAsSeen.add(message.getId());
    }

    return new Intent(context, MessageNotifActionReceiver.class)
        .setAction(NotificationAction.MARK_AS_SEEN_AND_OPEN_INBOX.name())
        .putStringArrayListExtra(INTENT_KEY_MESSAGE_ID_LIST, messageIdsToMarkAsSeen);
  }

  /**
   * Mark <var>unreadMessage</var> as seen and then open the message.
   */
  @CheckResult
  public static Intent createMarkAsSeenAndOpenMessageIntent(Context context, Message unreadMessage, MoshiAdapter moshiAdapter) {
    return new Intent(context, MessageNotifActionReceiver.class)
        .setAction(NotificationAction.MARK_AS_SEEN_AND_OPEN_MESSAGE.name())
        .putExtra(INTENT_KEY_MESSAGE_JSON, moshiAdapter.create(Message.class).toJson(unreadMessage));
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    Dank.dependencyInjector().inject(this);

    //noinspection ConstantConditions
    switch (NotificationAction.valueOf(intent.getAction())) {
      case DIRECT_REPLY: {
        parseMessage(intent.getStringExtra(INTENT_KEY_MESSAGE_JSON))
            .flatMapCompletable(message -> {
              // Note: dismiss notification after calling CheckUnreadMessagesJobService.refreshNotifications()
              // so that the summary notif gets removed first. Otherwise, the summary notif goes into a gray
              // color "disabled" state that is visible for a short time if there are no more individual notifs
              // available. CheckUnreadMessagesJobService.refreshNotifications() will dismiss all notifs if no
              // unseen/unread messages are present, so the summary gets removed too.
              return Completable
                  .fromAction(() -> {
                    Bundle directReplyResult = RemoteInput.getResultsFromIntent(intent);
                    String replyText = directReplyResult.getString(KEY_DIRECT_REPLY_MESSAGE);

                    // Send button is only enabled when the message is non-empty.
                    //noinspection ConstantConditions
                    MessageNotifActionsJobService.sendDirectReply(context, message, moshiAdapter.get(), replyText);
                  })
                  .andThen(Dank.messagesNotifManager().markMessageNotifAsSeen(message))
                  .andThen(Completable.fromAction(() -> MessageNotifActionsJobService.markAsRead(context, moshiAdapter.get(), message)))
                  .andThen(Completable.fromAction(() -> CheckUnreadMessagesJobService.refreshNotifications(context)))
                  .andThen(Dank.messagesNotifManager().dismissNotification(context, message));
            })
            .subscribe(doNothingCompletable(), error -> {
              Timber.e(error, "Couldn't send direct reply");
              Toast.makeText(context, R.string.common_unknown_error_message, Toast.LENGTH_LONG).show();
            });
        break;
      }

      case MARK_AS_READ: {
        // Offload work to a service (because Receivers are destroyed immediately) and refresh
        // the notifs so that the summary notif gets canceled if no more notifs are present.
        parseMessageArray(intent.getStringExtra(INTENT_KEY_MESSAGE_ARRAY_JSON))
            .flatMapCompletable(messages -> Dank.messagesNotifManager().markMessageNotifAsSeen(messages)
                .andThen(Completable.fromAction(() -> CheckUnreadMessagesJobService.refreshNotifications(context)))
                .andThen(Completable.fromAction(() -> MessageNotifActionsJobService.markAsRead(context, moshiAdapter.get(), messages)))
                .andThen(Dank.messagesNotifManager().dismissNotification(context, messages))
            )
            .subscribe();
        break;
      }

      case MARK_ALL_AS_READ: {
        List<String> messageIdsToMarkAsRead = intent.getStringArrayListExtra(INTENT_KEY_MESSAGE_ID_LIST);
        Dank.messagesNotifManager()
            .markMessageNotifAsSeen(messageIdsToMarkAsRead)
            .andThen(Completable.fromAction(() -> CheckUnreadMessagesJobService.refreshNotifications(context)))
            .andThen(Completable.fromAction(() -> MessageNotifActionsJobService.markAllAsRead(context)))
            .subscribe();
        break;
      }

      // This action gets called only when all the notifs are dismissed, so we don't need to refresh the notif again.
      case MARK_AS_SEEN: {
        List<String> messageIdsToMarkAsSeen = intent.getStringArrayListExtra(INTENT_KEY_MESSAGE_ID_LIST);
        Dank.messagesNotifManager()
            .markMessageNotifAsSeen(messageIdsToMarkAsSeen)
            .subscribe();
        break;
      }

      // This action is also performed only when a notification is tapped,
      // where it gets dismissed. So refreshing notifs isn't required.
      case MARK_AS_SEEN_AND_OPEN_INBOX: {
        List<String> messageIdsToMarkAsSeen = intent.getStringArrayListExtra(INTENT_KEY_MESSAGE_ID_LIST);
        Dank.messagesNotifManager()
            .markMessageNotifAsSeen(messageIdsToMarkAsSeen)
            .subscribe(() -> {
              Intent unreadInboxIntent = InboxActivity.intent(context, InboxFolder.UNREAD);
              unreadInboxIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              context.startActivity(unreadInboxIntent);
            });
        break;
      }

      // This action is also performed only when a notification is tapped,
      // where it gets dismissed. So refreshing notifs isn't required.
      case MARK_AS_SEEN_AND_OPEN_MESSAGE: {
        String messageJson = intent.getStringExtra(INTENT_KEY_MESSAGE_JSON);

        parseMessage(messageJson)
            .flatMap(message -> Dank.messagesNotifManager().markMessageNotifAsSeen(message.getId()).andThen(Single.just(message)))
            .subscribe(message -> {
              Intent openIntent;

              if (message.isComment()) {
                String messageUrlPath = message.getContext();
                String messageUrl = "https://reddit.com" + messageUrlPath;
                RedditSubmissionLink commentLink = (RedditSubmissionLink) urlParser.get().parse(messageUrl);
                openIntent = SubmissionPageLayoutActivity.intent(context, commentLink, null, message);

              } else {
                //noinspection ConstantConditions
                String secondPartyName = JrawUtils2.secondPartyName(
                    context.getResources(),
                    message,
                    userSessionRepository.get().loggedInUserName());
                //noinspection ConstantConditions
                openIntent = PrivateMessageThreadActivity.intent(context, message, secondPartyName, null);
              }

              openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              context.startActivity(openIntent);
            });
        break;
      }

      default:
        throw new UnsupportedOperationException("Unknown action: " + intent.getAction());
    }
  }

  @CheckResult
  private Single<Message> parseMessage(String messageJson) {
    return Single.fromCallable(() -> moshiAdapter.get().create(Message.class).fromJson(messageJson));
  }

  @CheckResult
  private Single<Message[]> parseMessageArray(String messageArrayJson) {
    return Single.fromCallable(() -> moshiAdapter.get().create(Message[].class).fromJson(messageArrayJson));
  }
}
