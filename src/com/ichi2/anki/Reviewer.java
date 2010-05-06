package com.ichi2.anki;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.ichi2.utils.DiffEngine;
import com.tomgibara.android.veecheck.util.PrefSettings;

public class Reviewer extends Activity {
	
	/**
	 * Tag for logging messages
	 */
	private static final String TAG = "Ankidroid";
	
	/**
	 * Result codes that are returned when this activity finishes.
	 */
	public static final int RESULT_DECK_NOT_LOADED = 5;
	public static final int RESULT_SESSION_COMPLETED = 1;
	public static final int RESULT_NO_MORE_CARDS = 2;
	
	public static final int EDIT_CURRENT_CARD = 2;
	
	/**
	 * Menus
	 */
	private static final int MENU_SUSPEND = 0;
	private static final int MENU_EDIT = 1;
	
	/**
	 * Max and min size of the font of the questions and answers
	 */
	private static final int MAX_FONT_SIZE = 14;
	private static final int MIN_FONT_SIZE = 3;
	
	/**
	 * Variables to hold preferences
	 */
	private boolean prefCorporalPunishments;
	private boolean prefTimerAndWhiteboard;
	private boolean prefWriteAnswers;
	private String prefDeckFilename;
	
	public String cardTemplate;
	
	/**
	 * Variables to hold layout objects that we need to update or handle events for
	 */
	private WebView mCard;
	private ToggleButton mToggleWhiteboard, mFlipCard;
	private EditText mAnswerField;
	private Button mEase0, mEase1, mEase2, mEase3;
	private Chronometer mCardTimer;
	private Whiteboard mWhiteboard;
	private ProgressDialog mProgressDialog;
	
	private Card mCurrentCard;
	private static Card editorCard; // To be assigned as the currentCard or a new card to be sent to and from the editor
	private long mSessionTimeLimit;
	private int mSessionCurrReps;

	// Handler for the flip toogle button, between the question and the answer
	// of a card
	private CompoundButton.OnCheckedChangeListener mFlipCardHandler = new CompoundButton.OnCheckedChangeListener()
	{
		//@Override
		public void onCheckedChanged(CompoundButton buttonView, boolean showAnswer)
		{
			Log.i(TAG, "Flip card changed:");
			if (showAnswer)
				displayCardAnswer();
			else
				displayCardQuestion();
		}
	};
	
	// Handler for the Whiteboard toggle button.
	CompoundButton.OnCheckedChangeListener mToggleOverlayHandler = new CompoundButton.OnCheckedChangeListener()
	{
		public void onCheckedChanged(CompoundButton btn, boolean state)
		{
			setOverlayState(state);
		}
	};
	
	private View.OnClickListener mSelectEaseHandler = new View.OnClickListener()
	{
		public void onClick(View view)
		{
			int ease;
			switch (view.getId())
			{
			case R.id.ease1:
				ease = 1;
				if (prefCorporalPunishments)
				{
					Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
					v.vibrate(500);
				}
				break;
			case R.id.ease2:
				ease = 2;
				break;
			case R.id.ease3:
				ease = 3;
				break;
			case R.id.ease4:
				ease = 4;
				break;
			default:
				ease = 0;
				return;
			}
			
			Reviewer.this.mSessionCurrReps++; // increment number reps counter
			DeckTask.launchDeckTask(
					DeckTask.TASK_TYPE_ANSWER_CARD,
					mAnswerCardHandler,
					new DeckTask.TaskData(ease, AnkiDroidApp.deck(), mCurrentCard));
		}
	};

	DeckTask.TaskListener mUpdateCardHandler = new DeckTask.TaskListener()
    {
        public void onPreExecute() {
            mProgressDialog = ProgressDialog.show(Reviewer.this, "", "Saving changes...", true);
        }

        public void onPostExecute(DeckTask.TaskData result) {

            // Set the correct value for the flip card button - That triggers the
            // listener which displays the question of the card
            mFlipCard.setChecked(false);
            mWhiteboard.clear();
            mCardTimer.setBase(SystemClock.elapsedRealtime());
            mCardTimer.start();

            mProgressDialog.dismiss();
        }

        public void onProgressUpdate(DeckTask.TaskData... values) 
        {
            mCurrentCard = values[0].getCard();
        }
    };
	
	private DeckTask.TaskListener mAnswerCardHandler = new DeckTask.TaskListener()
	{
	    boolean sessioncomplete;
	    boolean nomorecards;

		public void onPreExecute() {
			Reviewer.this.setProgressBarIndeterminateVisibility(true);
			disableControls();
		}

		public void onPostExecute(DeckTask.TaskData result) {
		    // Check for no more cards before session complete. If they are both true,
			// no more cards will take precedence when returning to study options.
			if (nomorecards)
			{
				Reviewer.this.setResult(RESULT_NO_MORE_CARDS);
				Reviewer.this.finish();
			} else if (sessioncomplete)
			{
			    Reviewer.this.setResult(RESULT_SESSION_COMPLETED);
			    Reviewer.this.finish();
			}
		}

		public void onProgressUpdate(DeckTask.TaskData... values) {
			sessioncomplete = false;
			nomorecards = false;

		    // Check to see if session rep or time limit has been reached
		    Deck deck = AnkiDroidApp.deck();
		    long sessionRepLimit = deck.getSessionRepLimit();
		    long sessionTime = deck.getSessionTimeLimit();
		    Toast sessionMessage = null;

		    if( (sessionRepLimit > 0) && (Reviewer.this.mSessionCurrReps >= sessionRepLimit) )
		    {
		    	sessioncomplete = true;
		    	sessionMessage = Toast.makeText(Reviewer.this, "Session question limit reached", Toast.LENGTH_SHORT);
		    } else if( (sessionTime > 0) && (System.currentTimeMillis() >= Reviewer.this.mSessionTimeLimit) ) //Check to see if the session time limit has been reached
		    {
		        // session time limit reached, flag for halt once async task has completed.
		        sessioncomplete = true;
		        sessionMessage = Toast.makeText(Reviewer.this, "Session time limit reached", Toast.LENGTH_SHORT);

		    } else {
		        // session limits not reached, show next card
		        Card newCard = values[0].getCard();

		        // If the card is null means that there are no more cards scheduled for review.
		        if (newCard == null)
		        {
		        	nomorecards = true;
		        	return;
		        }
		        
		        // Start reviewing next card
		        Reviewer.this.mCurrentCard = newCard;
		        Reviewer.this.setProgressBarIndeterminateVisibility(false);
		        Reviewer.this.enableControls();
		        Reviewer.this.reviewNextCard();
		    }

			// Show a message to user if a session limit has been reached.
			if (sessionMessage != null)
				sessionMessage.show();
		}

	};
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		// Make sure a deck is loaded before continuing.
		if (AnkiDroidApp.deck() == null)
		{
			setResult(RESULT_DECK_NOT_LOADED);
			finish();
		}
		
		// Remove the status bar and make title bar progress available
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
		
		restorePreferences();
		initLayout(R.layout.flashcard_portrait);
		updateTitle();
		cardTemplate = getResources().getString(R.string.card_template);
		
		// Initialize session limits
		long timelimit = AnkiDroidApp.deck().getSessionTimeLimit() * 1000;
		Log.i(TAG, "SessionTimeLimit: " + timelimit + " ms.");
		mSessionTimeLimit = System.currentTimeMillis() + timelimit;
		mSessionCurrReps = 0;
		
		/* Load the first card and start reviewing.
		 * Uses the answer card task to load a card, but since we send null
		 * as the card to answer, no card will be answered.
		 */
		DeckTask.launchDeckTask(
				DeckTask.TASK_TYPE_ANSWER_CARD, 
				mAnswerCardHandler, 
				new DeckTask.TaskData(
						0,
						AnkiDroidApp.deck(),
						null));
	}
	
	// Set the content view to the one provided and initialize accessors.
	private void initLayout(Integer layout)
	{
		setContentView(layout);

		mCard = (WebView) findViewById(R.id.flashcard);
		mEase0 = (Button) findViewById(R.id.ease1);
		mEase1 = (Button) findViewById(R.id.ease2);
		mEase2 = (Button) findViewById(R.id.ease3);
		mEase3 = (Button) findViewById(R.id.ease4);
		mCardTimer = (Chronometer) findViewById(R.id.card_time);
		mFlipCard = (ToggleButton) findViewById(R.id.flip_card);
		mToggleWhiteboard = (ToggleButton) findViewById(R.id.toggle_overlay);
		mWhiteboard = (Whiteboard) findViewById(R.id.whiteboard);
		mAnswerField = (EditText) findViewById(R.id.answer_field);

		hideControls();

		mEase0.setOnClickListener(mSelectEaseHandler);
		mEase1.setOnClickListener(mSelectEaseHandler);
		mEase2.setOnClickListener(mSelectEaseHandler);
		mEase3.setOnClickListener(mSelectEaseHandler);
		mFlipCard.setChecked(true); // Fix for mFlipCardHandler not being called on first deck load.
		mFlipCard.setOnCheckedChangeListener(mFlipCardHandler);
		mToggleWhiteboard.setOnCheckedChangeListener(mToggleOverlayHandler);

		mCard.setFocusable(false);
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuItem item;
		item = menu.add(Menu.NONE, MENU_SUSPEND, Menu.NONE, R.string.menu_suspend_card);
		item.setIcon(android.R.drawable.ic_menu_close_clear_cancel);
		item = menu.add(Menu.NONE, MENU_EDIT, Menu.NONE, R.string.menu_edit_card);
		item.setIcon(android.R.drawable.ic_menu_edit);
		
		return true;
	}
	
	/** Handles item selections */
	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId())
		{
		case MENU_SUSPEND:
			mFlipCard.setChecked(true);
			DeckTask.launchDeckTask(DeckTask.TASK_TYPE_SUSPEND_CARD, 
					mAnswerCardHandler,
					new DeckTask.TaskData(0, AnkiDroidApp.deck(), mCurrentCard));
			return true;
		case MENU_EDIT:
			editorCard = mCurrentCard;
			Intent editCard = new Intent(this, CardEditor.class);
			startActivityForResult(editCard, EDIT_CURRENT_CARD);
			return true;
		}
		return false;
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		
		if (requestCode == EDIT_CURRENT_CARD)
        {
            		    DeckTask.launchDeckTask(
                                DeckTask.TASK_TYPE_UPDATE_FACT,
                                mUpdateCardHandler,
                                new DeckTask.TaskData(0, AnkiDroidApp.deck(), mCurrentCard));
            //TODO: code to save the changes made to the current card.
            mFlipCard.setChecked(true);
            displayCardQuestion();
		}
	}
	
	public static Card getEditorCard () {
        return editorCard;
    }

	private void showControls()
	{
		mCard.setVisibility(View.VISIBLE);
		mEase0.setVisibility(View.VISIBLE);
		mEase1.setVisibility(View.VISIBLE);
		mEase2.setVisibility(View.VISIBLE);
		mEase3.setVisibility(View.VISIBLE);
		mFlipCard.setVisibility(View.VISIBLE);
		
		if (!prefTimerAndWhiteboard)
		{
			mCardTimer.setVisibility(View.GONE);
			mToggleWhiteboard.setVisibility(View.GONE);
			mWhiteboard.setVisibility(View.GONE);
		} else
		{
			mCardTimer.setVisibility(View.VISIBLE);
			mToggleWhiteboard.setVisibility(View.VISIBLE);
			if (mToggleWhiteboard.isChecked())
			{
				mWhiteboard.setVisibility(View.VISIBLE);
			}
		}
		
		if (!prefWriteAnswers)
		{
			mAnswerField.setVisibility(View.GONE);
		} else
		{
			mAnswerField.setVisibility(View.VISIBLE);
		}
	}
	
	public void setOverlayState(boolean enabled)
	{
		mWhiteboard.setVisibility((enabled) ? View.VISIBLE : View.GONE);
	}
	
	private void hideControls()
	{
		mCard.setVisibility(View.GONE);
		mEase0.setVisibility(View.GONE);
		mEase1.setVisibility(View.GONE);
		mEase2.setVisibility(View.GONE);
		mEase3.setVisibility(View.GONE);
		mFlipCard.setVisibility(View.GONE);
		mCardTimer.setVisibility(View.GONE);
		mToggleWhiteboard.setVisibility(View.GONE);
		mWhiteboard.setVisibility(View.GONE);
		mAnswerField.setVisibility(View.GONE);
	}
	
	private void enableControls()
	{
		mCard.setEnabled(true);
		mEase0.setEnabled(true);
		mEase1.setEnabled(true);
		mEase2.setEnabled(true);
		mEase3.setEnabled(true);
		mFlipCard.setEnabled(true);
		mCardTimer.setEnabled(true);
		mToggleWhiteboard.setEnabled(true);
		mWhiteboard.setEnabled(true);
		mAnswerField.setEnabled(true);
	}
	
	private void disableControls()
	{
		mCard.setEnabled(false);
		mEase0.setEnabled(false);
		mEase1.setEnabled(false);
		mEase2.setEnabled(false);
		mEase3.setEnabled(false);
		mFlipCard.setEnabled(false);
		mCardTimer.setEnabled(false);
		mToggleWhiteboard.setEnabled(false);
		mWhiteboard.setEnabled(false);
		mAnswerField.setEnabled(false);
	}
	
	private SharedPreferences restorePreferences()
	{
		SharedPreferences preferences = PrefSettings.getSharedPrefs(getBaseContext());
		prefCorporalPunishments = preferences.getBoolean("corporalPunishments", false);
		prefTimerAndWhiteboard = preferences.getBoolean("timerAndWhiteboard", true);
		prefWriteAnswers = preferences.getBoolean("writeAnswers", false);
		prefDeckFilename = preferences.getString("deckFilename", "");

		return preferences;
	}
	
	private void updateCard(String content)
	{
		Log.i(TAG, "updateCard");

		content = Sound.extractSounds(prefDeckFilename, content);
		content = Image.loadImages(prefDeckFilename, content);
		
		// We want to modify the font size depending on how long is the content
		// Replace each <br> with 15 spaces, then remove all html tags and spaces
		String realContent = content.replaceAll("\\<br.*?\\>", "               ");
		realContent = realContent.replaceAll("\\<.*?\\>", "");
		realContent = realContent.replaceAll("&nbsp;", " ");

		// Calculate the size of the font depending on the length of the content
		int size = Math.max(MIN_FONT_SIZE, MAX_FONT_SIZE - (int)(realContent.length()/5));
		mCard.getSettings().setDefaultFontSize(size);

		//In order to display the bold style correctly, we have to change font-weight to 700
		content = content.replaceAll("font-weight:600;", "font-weight:700;");

		Log.i(TAG, "content card = \n" + content);
		String card = cardTemplate.replace("::content::", content);
		mCard.loadDataWithBaseURL("", card, "text/html", "utf-8", null);
		Sound.playSounds();
	}
	
	private void reviewNextCard()
	{
		updateTitle();
		mFlipCard.setChecked(false);
		
		mWhiteboard.clear();
		mCardTimer.setBase(SystemClock.elapsedRealtime());
		mCardTimer.start();
	}
	
	private void displayCardQuestion()
	{
		updateCard(mCurrentCard.question);
		
		showControls();
		//mFlipCard.setChecked(false);
		
		mEase0.setVisibility(View.GONE);
		mEase1.setVisibility(View.GONE);
		mEase2.setVisibility(View.GONE);
		mEase3.setVisibility(View.GONE);

		// If the user wants to write the answer
		if(prefWriteAnswers)
			mAnswerField.setVisibility(View.VISIBLE);
		
		mFlipCard.requestFocus();
	}
	
	private void displayCardAnswer()
	{
		Log.i(TAG, "displayCardAnswer");
		
		mCardTimer.stop();
		mWhiteboard.lock();

		mEase0.setVisibility(View.VISIBLE);
		mEase1.setVisibility(View.VISIBLE);
		mEase2.setVisibility(View.VISIBLE);
		mEase3.setVisibility(View.VISIBLE);

		mAnswerField.setVisibility(View.GONE);

		mEase2.requestFocus();

		// If the user wrote an answer
		if(prefWriteAnswers)
		{
			if(mCurrentCard != null)
			{
				// Obtain the user answer and the correct answer
				String userAnswer = mAnswerField.getText().toString();
				String correctAnswer = (String) mCurrentCard.answer.subSequence(
						mCurrentCard.answer.indexOf(">")+1,
						mCurrentCard.answer.lastIndexOf("<"));

				// Obtain the diff and send it to updateCard
				DiffEngine diff = new DiffEngine();
				updateCard(diff.diff_prettyHtml(
						diff.diff_main(userAnswer, correctAnswer)) +
						"<br/>" + mCurrentCard.answer);
			}
			else
			{
				updateCard("");
			}
		}
		else
		{
			updateCard(mCurrentCard.answer);
		}
	}
	
	private void updateTitle()
	{
		Deck deck = AnkiDroidApp.deck();
		String unformattedTitle = getResources().getString(R.string.studyoptions_window_title);
		setTitle(String.format(unformattedTitle, deck.deckName, deck.revCount + deck.failedSoonCount, deck.cardCount));
	}
}
