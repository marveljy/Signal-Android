package org.thoughtcrime.securesms.jobs;


import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientPreferenceDatabase;
import org.thoughtcrime.securesms.database.RecipientPreferenceDatabase.RecipientsPreferences;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;

public class RetrieveProfileAvatarJob extends ContextJob implements InjectableType {

  private static final String TAG = RetrieveProfileAvatarJob.class.getSimpleName();

  private static final int MAX_PROFILE_SIZE_BYTES = 20 * 1024 * 1024;

  @Inject SignalServiceMessageReceiver receiver;

  private final String    profileAvatar;
  private final Recipient recipient;

  public RetrieveProfileAvatarJob(Context context, Recipient recipient, String profileAvatar) {
    super(context, JobParameters.newBuilder()
                                .withGroupId(RetrieveProfileAvatarJob.class.getSimpleName() + recipient.getAddress().serialize())
                                .withRequirement(new NetworkRequirement(context))
                                .create());

    this.recipient     = recipient;
    this.profileAvatar = profileAvatar;
  }

  @Override
  public void onAdded() {}

  @Override
  public void onRun() throws IOException {
    RecipientPreferenceDatabase     database              = DatabaseFactory.getRecipientPreferenceDatabase(context);
    Optional<RecipientsPreferences> recipientsPreferences = database.getRecipientsPreferences(recipient.getAddress());

    if (!recipientsPreferences.isPresent()) {
      Log.w(TAG, "Recipient preference row is gone!");
      return;
    }

    if (recipientsPreferences.get().getProfileKey() == null) {
      Log.w(TAG, "Recipient profile key is gone!");
      return;
    }

    if (Util.equals(profileAvatar, recipientsPreferences.get().getProfileAvatar())) {
      Log.w(TAG, "Already retrieved profile avatar: " + profileAvatar);
      return;
    }

    if (TextUtils.isEmpty(profileAvatar)) {
      Log.w(TAG, "Removing profile avatar for: " + recipient.getAddress().serialize());
      AvatarHelper.delete(context, recipient.getAddress());
      return;
    }

    File downloadDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());

    try {
      InputStream avatarStream       = receiver.retrieveProfileAvatar(profileAvatar, downloadDestination, recipientsPreferences.get().getProfileKey(), MAX_PROFILE_SIZE_BYTES);
      File        decryptDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());

      Util.copy(avatarStream, new FileOutputStream(decryptDestination));
      decryptDestination.renameTo(AvatarHelper.getAvatarFile(context, recipient.getAddress()));
    } finally {
      if (downloadDestination != null) downloadDestination.delete();
    }

    database.setProfileAvatar(recipient.getAddress(), profileAvatar);
    Recipient.clearCache(context);
  }

  @Override
  public boolean onShouldRetry(Exception e) {
    Log.w(TAG, e);
    if (e instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onCanceled() {

  }
}