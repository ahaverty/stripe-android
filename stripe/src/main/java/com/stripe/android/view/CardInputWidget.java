package com.stripe.android.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.ColorInt;
import android.support.annotation.IdRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.text.InputFilter;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Transformation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.stripe.android.R;
import com.stripe.android.model.Card;
import com.stripe.android.util.CardUtils;
import com.stripe.android.util.LoggingUtils;
import com.stripe.android.util.StripeTextUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * A card input widget that handles all animation on its own.
 */
public class CardInputWidget extends LinearLayout {

    private static final String PEEK_TEXT_COMMON = "4242";
    private static final String PEEK_TEXT_DINERS = "88";
    private static final String PEEK_TEXT_AMEX = "34343";

    private static final String CVC_PLACEHOLDER_COMMON = "CVC";
    private static final String CVC_PLACEHOLDER_AMEX = "2345";

    // These intentionally include a space at the end.
    private static final String HIDDEN_TEXT_AMEX = "3434 343434 ";
    private static final String HIDDEN_TEXT_COMMON = "4242 4242 4242 ";

    private static final String FULL_SIZING_CARD_TEXT = "4242 4242 4242 4242";
    private static final String FULL_SIZING_DATE_TEXT = "MM/YY";

    private static final String EXTRA_CARD_VIEWED = "extra_card_viewed";
    private static final String EXTRA_SUPER_STATE = "extra_super_state";

    // This value is used to ensure that onSaveInstanceState is called
    // in the event that the user doesn't give this control an ID.
    private static final
    @IdRes
    int DEFAULT_READER_ID = 42424242;

    private static final long ANIMATION_LENGTH = 150L;

    private static final Map<String, Integer> BRAND_RESOURCE_MAP =
            new HashMap<String, Integer>() {{
                put(Card.AMERICAN_EXPRESS, R.drawable.ic_amex);
                put(Card.DINERS_CLUB, R.drawable.ic_diners);
                put(Card.DISCOVER, R.drawable.ic_discover);
                put(Card.JCB, R.drawable.ic_jcb);
                put(Card.MASTERCARD, R.drawable.ic_mastercard);
                put(Card.VISA, R.drawable.ic_visa);
                put(Card.UNKNOWN, R.drawable.ic_unknown);
            }};

    private ImageView mCardIconImageView;
    private CardNumberEditText mCardNumberEditText;
    private boolean mCardNumberIsViewed = true;
    private StripeEditText mCvcNumberEditText;
    private ExpiryDateEditText mExpiryDateEditText;

    private FrameLayout mFrameLayout;

    private
    @ColorInt
    int mErrorColorInt;
    private
    @ColorInt
    int mTintColorInt;

    private boolean mIsAmEx;
    private boolean mInitFlag;

    private int mTotalLengthInPixels;

    private DimensionOverrideSettings mDimensionOverrides;
    private PlacementParameters mPlacementParameters;

    public CardInputWidget(Context context) {
        super(context);
        initView(null);
    }

    public CardInputWidget(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView(attrs);
    }

    public CardInputWidget(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView(attrs);
    }

    /**
     * Gets a {@link Card} object from the user input, if all fields are valid. If not, returns
     * {@code null}.
     *
     * @return a valid {@link Card} object based on user input, or {@code null} if any field is
     * invalid
     */
    @Nullable
    public Card getCard() {
        String cardNumber = mCardNumberEditText.getCardNumber();
        int[] cardDate = mExpiryDateEditText.getValidDateFields();
        if (cardNumber == null || cardDate == null || cardDate.length != 2) {
            return null;
        }

        // CVC/CVV is the only field not validated by the entry control itself, so we check here.
        int requiredLength = mIsAmEx ? CardUtils.CVC_LENGTH_AMEX : CardUtils.CVC_LENGTH_COMMON;
        String cvcValue = mCvcNumberEditText.getText().toString();
        if (StripeTextUtils.isBlank(cvcValue) || cvcValue.length() != requiredLength) {
            return null;
        }

        return new Card(cardNumber, cardDate[0], cardDate[1], cvcValue)
                .addLoggingToken(LoggingUtils.CARD_WIDGET_TOKEN);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onInterceptTouchEvent(ev);
        }

        StripeEditText focusEditText = getFocusRequestOnTouch((int) ev.getX());
        if (focusEditText != null) {
            focusEditText.requestFocus();
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    public void setCardNumber(String cardNumber) {
        mCardNumberEditText.setText(cardNumber);
    }

    public void setExpiryDate(String expiryDate) {
        mExpiryDateEditText.setText(expiryDate);
    }

    public void clearCvc() {
        mCvcNumberEditText.setText("");
    }

    public void clearInputs() {
        setCardNumber("");
        setExpiryDate("");
        clearCvc();
        mCvcNumberEditText.clearFocus();
        mExpiryDateEditText.clearFocus();
        mCvcNumberEditText.clearFocus();
    }

    public void requestFocusOnCard() {
        mCardNumberEditText.requestFocus();
    }

    public void requestFocusOnExpiry() {
        mExpiryDateEditText.requestFocus();
    }

    public void requestFocusOnCvc() {
        mCvcNumberEditText.requestFocus();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_SUPER_STATE, super.onSaveInstanceState());
        bundle.putBoolean(EXTRA_CARD_VIEWED, mCardNumberIsViewed);
        return bundle;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state instanceof Bundle) {
            Bundle bundleState = (Bundle) state;
            mCardNumberIsViewed = bundleState.getBoolean(EXTRA_CARD_VIEWED, true);
            updateSpaceSizes(mCardNumberIsViewed);
            mTotalLengthInPixels = getFrameWidth();
            int cardMargin, dateMargin, cvcMargin;
            if (mCardNumberIsViewed) {
                cardMargin = 0;
                dateMargin = mPlacementParameters.cardWidth
                        + mPlacementParameters.cardDateSeparation;
                cvcMargin = mTotalLengthInPixels;
            } else {
                cardMargin = -1 * mPlacementParameters.hiddenCardWidth;
                dateMargin = mPlacementParameters.peekCardWidth
                        + mPlacementParameters.cardDateSeparation;
                cvcMargin = dateMargin
                        + mPlacementParameters.dateWidth
                        + mPlacementParameters.dateCvcSeparation;
            }

            setLayoutValues(mPlacementParameters.cardWidth, cardMargin, mCardNumberEditText);
            setLayoutValues(mPlacementParameters.dateWidth, dateMargin, mExpiryDateEditText);
            setLayoutValues(mPlacementParameters.cvcWidth, cvcMargin, mCvcNumberEditText);

            super.onRestoreInstanceState(bundleState.getParcelable(EXTRA_SUPER_STATE));
        } else {
            super.onRestoreInstanceState(state);
        }
    }

    /**
     * Checks on the horizontal position of a touch event to see if
     * that event needs to be associated with one of the controls even
     * without having actually touched it. This essentially gives a larger
     * touch surface to the controls. We return {@code null} if the user touches
     * actually inside the widget because no interception is necessary - the touch will
     * naturally give focus to that control, and we don't want to interfere with what
     * Android will naturally do in response to that touch.
     *
     * @param touchX distance in pixels from the left side of this control
     * @return a {@link StripeEditText} that needs to request focus, or {@code null}
     * if no such request is necessary.
     */
    @VisibleForTesting
    @Nullable
    StripeEditText getFocusRequestOnTouch(int touchX) {
        int frameStart = mFrameLayout.getLeft();

        if (mCardNumberIsViewed) {
            // Then our view is
            // |CARDVIEW||space||DATEVIEW|

            if (touchX < frameStart + mPlacementParameters.cardWidth) {
                // Then the card edit view will already handle this touch.
                return null;
            } else if (touchX < mPlacementParameters.cardTouchBufferLimit) {
                // Then we want to act like this was a touch on the card view
                return mCardNumberEditText;
            } else if (touchX < mPlacementParameters.dateStartPosition) {
                // Then we act like this was a touch on the date editor.
                return mExpiryDateEditText;
            } else {
                // Then the date editor will already handle this touch.
                return null;
            }
        } else {
            // Our view is
            // |PEEK||space||DATE||space||CVC|
            if (touchX < frameStart + mPlacementParameters.peekCardWidth) {
                // This was a touch on the card number editor, so we don't need to handle it.
                return null;
            } else if (touchX < mPlacementParameters.cardTouchBufferLimit) {
                // Then we need to act like the user touched the card editor
                return mCardNumberEditText;
            } else if (touchX < mPlacementParameters.dateStartPosition) {
                // Then we need to act like this was a touch on the date editor
                return mExpiryDateEditText;
            } else if (touchX < mPlacementParameters.dateStartPosition + mPlacementParameters.dateWidth) {
                // Just a regular touch on the date editor.
                return null;
            } else if (touchX < mPlacementParameters.dateRightTouchBufferLimit) {
                // We need to act like this was a touch on the date editor
                return mExpiryDateEditText;
            } else if (touchX < mPlacementParameters.cvcStartPosition) {
                // We need to act like this was a touch on the cvc editor.
                return mCvcNumberEditText;
            } else {
                return null;
            }
        }
    }

    @VisibleForTesting
    void setDimensionOverrideSettings(DimensionOverrideSettings dimensonOverrides) {
        mDimensionOverrides = dimensonOverrides;
    }

    @VisibleForTesting
    void setCardNumberIsViewed(boolean cardNumberIsViewed) {
        mCardNumberIsViewed = cardNumberIsViewed;
    }

    @NonNull
    @VisibleForTesting
    PlacementParameters getPlacementParameters() {
        return mPlacementParameters;
    }

    @VisibleForTesting
    void updateSpaceSizes(boolean isCardViewed) {
        int frameWidth = getFrameWidth();
        int frameStart = mFrameLayout.getLeft();
        if (frameWidth == 0) {
            // This is an invalid view state.
            return;
        }

        mPlacementParameters.cardWidth =
                getDesiredWidthInPixels(FULL_SIZING_CARD_TEXT, mCardNumberEditText);

        mPlacementParameters.dateWidth =
                getDesiredWidthInPixels(FULL_SIZING_DATE_TEXT, mExpiryDateEditText);

        @Card.CardBrand String brand = mCardNumberEditText.getCardBrand();
        mPlacementParameters.hiddenCardWidth =
                getDesiredWidthInPixels(getHiddenTextForBrand(brand), mCardNumberEditText);

        mPlacementParameters.cvcWidth =
                getDesiredWidthInPixels(getCvcPlaceHolderForBrand(brand), mCvcNumberEditText);

        mPlacementParameters.peekCardWidth =
                getDesiredWidthInPixels(getPeekCardTextForBrand(brand), mCardNumberEditText);

        if (isCardViewed) {
            mPlacementParameters.cardDateSeparation = frameWidth
                    - mPlacementParameters.cardWidth - mPlacementParameters.dateWidth;
            mPlacementParameters.cardTouchBufferLimit = frameStart
                    + mPlacementParameters.cardWidth + mPlacementParameters.cardDateSeparation / 2;
            mPlacementParameters.dateStartPosition = frameStart
                    + mPlacementParameters.cardWidth + mPlacementParameters.cardDateSeparation;
        } else {
            mPlacementParameters.cardDateSeparation = frameWidth / 2
                    - mPlacementParameters.peekCardWidth
                    - mPlacementParameters.dateWidth / 2;
            mPlacementParameters.dateCvcSeparation = frameWidth
                    - mPlacementParameters.peekCardWidth
                    - mPlacementParameters.cardDateSeparation
                    - mPlacementParameters.dateWidth
                    - mPlacementParameters.cvcWidth;

            mPlacementParameters.cardTouchBufferLimit = frameStart
                    + mPlacementParameters.peekCardWidth
                    + mPlacementParameters.cardDateSeparation / 2;
            mPlacementParameters.dateStartPosition = frameStart
                    + mPlacementParameters.peekCardWidth
                    + mPlacementParameters.cardDateSeparation;
            mPlacementParameters.dateRightTouchBufferLimit =
                    mPlacementParameters.dateStartPosition
                            + mPlacementParameters.dateWidth
                            + mPlacementParameters.dateCvcSeparation / 2;
            mPlacementParameters.cvcStartPosition = mPlacementParameters.dateStartPosition
                    + mPlacementParameters.dateWidth
                    + mPlacementParameters.dateCvcSeparation;
        }
    }

    private void setLayoutValues(int width, int margin, @NonNull StripeEditText editText) {
        FrameLayout.LayoutParams layoutParams =
                (FrameLayout.LayoutParams) editText.getLayoutParams();
        layoutParams.width = width;
        layoutParams.leftMargin = margin;
        editText.setLayoutParams(layoutParams);
    }

    private int getDesiredWidthInPixels(@NonNull String text, @NonNull StripeEditText editText) {
        return mDimensionOverrides == null
                ? (int) Layout.getDesiredWidth(text, editText.getPaint())
                : mDimensionOverrides.getPixelWidth(text, editText);
    }

    private int getFrameWidth() {
        return mDimensionOverrides == null
                ? mFrameLayout.getWidth()
                : mDimensionOverrides.getFrameWidth();
    }

    private void initView(AttributeSet attrs) {
        inflate(getContext(), R.layout.card_input_widget, this);

        // This ensures that onRestoreInstanceState is called
        // during rotations.
        if (getId() == NO_ID) {
            setId(DEFAULT_READER_ID);
        }

        setOrientation(LinearLayout.HORIZONTAL);
        setMinimumWidth(getResources().getDimensionPixelSize(R.dimen.card_widget_min_width));
        mPlacementParameters = new PlacementParameters();
        mCardIconImageView = (ImageView) findViewById(R.id.iv_card_icon);
        mCardNumberEditText = (CardNumberEditText) findViewById(R.id.et_card_number);
        mExpiryDateEditText = (ExpiryDateEditText) findViewById(R.id.et_expiry_date);
        mCvcNumberEditText = (StripeEditText) findViewById(R.id.et_cvc_number);

        mCardNumberIsViewed = true;

        mFrameLayout = (FrameLayout) findViewById(R.id.frame_container);
        mErrorColorInt = mCardNumberEditText.getDefaultErrorColorInt();
        mTintColorInt = mCardNumberEditText.getHintTextColors().getDefaultColor();
        if (attrs != null) {
            TypedArray a = getContext().getTheme().obtainStyledAttributes(
                    attrs,
                    R.styleable.CardInputView,
                    0, 0);

            try {
                mErrorColorInt =
                        a.getColor(R.styleable.CardInputView_cardTextErrorColor, mErrorColorInt);
                mTintColorInt =
                        a.getColor(R.styleable.CardInputView_cardTint, mTintColorInt);
            } finally {
                a.recycle();
            }
        }

        mCardNumberEditText.setErrorColor(mErrorColorInt);
        mExpiryDateEditText.setErrorColor(mErrorColorInt);
        mCvcNumberEditText.setErrorColor(mErrorColorInt);

        mCardNumberEditText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    scrollLeft();
                }
            }
        });

        mExpiryDateEditText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    scrollRight();
                }
            }
        });

        mCvcNumberEditText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    scrollRight();
                }
            }
        });

        mExpiryDateEditText.setDeleteEmptyListener(
                new CardInputWidget.BackUpFieldDeleteListener(mCardNumberEditText));

        mCvcNumberEditText.setDeleteEmptyListener(
                new CardInputWidget.BackUpFieldDeleteListener(mExpiryDateEditText));

        mCvcNumberEditText.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                updateIconCvc(mCardNumberEditText.getCardBrand(), hasFocus);
            }
        });

        mCardNumberEditText.setCardNumberCompleteListener(
                new CardNumberEditText.CardNumberCompleteListener() {
                    @Override
                    public void onCardNumberComplete() {
                        scrollRight();
                    }
                });

        mCardNumberEditText.setCardBrandChangeListener(
                new CardNumberEditText.CardBrandChangeListener() {
                    @Override
                    public void onCardBrandChanged(@NonNull @Card.CardBrand String brand) {
                        mIsAmEx = Card.AMERICAN_EXPRESS.equals(brand);
                        updateIcon(brand);
                        updateCvc(brand);
                    }
                });

        mExpiryDateEditText.setExpiryDateEditListener(new ExpiryDateEditText.ExpiryDateEditListener() {
            @Override
            public void onExpiryDateComplete() {
                mCvcNumberEditText.requestFocus();
            }
        });

        mCardNumberEditText.requestFocus();
    }

    private void scrollLeft() {
        if (mCardNumberIsViewed) {
            return;
        }

        final int dateStartPosition =
                mPlacementParameters.peekCardWidth + mPlacementParameters.cardDateSeparation;
        final int cvcStartPosition =
                dateStartPosition
                        + mPlacementParameters.dateWidth + mPlacementParameters.dateCvcSeparation;

        updateSpaceSizes(true);

        final int startPoint = ((FrameLayout.LayoutParams)
                mCardNumberEditText.getLayoutParams()).leftMargin;
        Animation slideCardLeftAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                super.applyTransformation(interpolatedTime, t);
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) mCardNumberEditText.getLayoutParams();
                params.leftMargin = (int) (startPoint * (1 - interpolatedTime));
                mCardNumberEditText.setLayoutParams(params);
            }
        };

        final int dateDestination =
                mPlacementParameters.cardWidth + mPlacementParameters.cardDateSeparation;
        Animation slideDateLeftAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                super.applyTransformation(interpolatedTime, t);
                int tempValue =
                        (int) (interpolatedTime * dateDestination
                                + (1 - interpolatedTime) * dateStartPosition);
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) mExpiryDateEditText.getLayoutParams();
                params.leftMargin = tempValue;
                mExpiryDateEditText.setLayoutParams(params);
            }
        };

        final int cvcDestination = cvcStartPosition + (dateDestination - dateStartPosition);
        Animation slideCvcLeftAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                super.applyTransformation(interpolatedTime, t);
                int tempValue =
                        (int) (interpolatedTime * cvcDestination
                                + (1 - interpolatedTime) * cvcStartPosition);
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) mCvcNumberEditText.getLayoutParams();
                params.leftMargin = tempValue;
                params.rightMargin = 0;
                params.width = mPlacementParameters.cvcWidth;
                mCvcNumberEditText.setLayoutParams(params);
            }
        };

        slideCardLeftAnimation.setAnimationListener(new AnimationEndListener() {
            @Override
            public void onAnimationEnd(Animation animation) {
                mCardNumberEditText.requestFocus();
            }
        });

        slideCardLeftAnimation.setDuration(ANIMATION_LENGTH);
        slideDateLeftAnimation.setDuration(ANIMATION_LENGTH);
        slideCvcLeftAnimation.setDuration(ANIMATION_LENGTH);

        AnimationSet animationSet = new AnimationSet(true);
        animationSet.addAnimation(slideCardLeftAnimation);
        animationSet.addAnimation(slideDateLeftAnimation);
        animationSet.addAnimation(slideCvcLeftAnimation);
        mFrameLayout.startAnimation(animationSet);
        mCardNumberIsViewed = true;
    }

    private void scrollRight() {
        if (!mCardNumberIsViewed) {
            return;
        }

        final int dateStartMargin = mPlacementParameters.cardWidth
                + mPlacementParameters.cardDateSeparation;

        updateSpaceSizes(false);

        Animation slideCardRightAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                super.applyTransformation(interpolatedTime, t);
                FrameLayout.LayoutParams cardParams =
                        (FrameLayout.LayoutParams) mCardNumberEditText.getLayoutParams();
                cardParams.leftMargin =
                        (int) (-1 * mPlacementParameters.hiddenCardWidth * interpolatedTime);
                mCardNumberEditText.setLayoutParams(cardParams);
            }
        };

        final int dateDestination =
                mPlacementParameters.peekCardWidth
                        + mPlacementParameters.cardDateSeparation;

        Animation slideDateRightAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                super.applyTransformation(interpolatedTime, t);
                int tempValue =
                        (int) (interpolatedTime * dateDestination
                                + (1 - interpolatedTime) * dateStartMargin);
                FrameLayout.LayoutParams dateParams =
                        (FrameLayout.LayoutParams) mExpiryDateEditText.getLayoutParams();
                dateParams.leftMargin = tempValue;
                mExpiryDateEditText.setLayoutParams(dateParams);
            }
        };

        final int cvcDestination =
                mPlacementParameters.peekCardWidth
                        + mPlacementParameters.cardDateSeparation
                        + mPlacementParameters.dateWidth
                        + mPlacementParameters.dateCvcSeparation;
        final int cvcStartMargin = cvcDestination + (dateStartMargin - dateDestination);

        Animation slideCvcRightAnimation = new Animation() {
            @Override
            protected void applyTransformation(float interpolatedTime, Transformation t) {
                super.applyTransformation(interpolatedTime, t);
                int tempValue =
                        (int) (interpolatedTime * cvcDestination
                                + (1 - interpolatedTime) * cvcStartMargin);
                FrameLayout.LayoutParams cardParams =
                        (FrameLayout.LayoutParams) mCvcNumberEditText.getLayoutParams();
                cardParams.leftMargin = tempValue;
                cardParams.rightMargin = 0;
                cardParams.width = mPlacementParameters.cvcWidth;
                mCvcNumberEditText.setLayoutParams(cardParams);
            }
        };

        slideCardRightAnimation.setDuration(ANIMATION_LENGTH);
        slideDateRightAnimation.setDuration(ANIMATION_LENGTH);
        slideCvcRightAnimation.setDuration(ANIMATION_LENGTH);

        slideCardRightAnimation.setAnimationListener(new AnimationEndListener() {
            @Override
            public void onAnimationEnd(Animation animation) {
                mExpiryDateEditText.requestFocus();
            }
        });

        AnimationSet animationSet = new AnimationSet(true);
        animationSet.addAnimation(slideCardRightAnimation);
        animationSet.addAnimation(slideDateRightAnimation);
        animationSet.addAnimation(slideCvcRightAnimation);

        mFrameLayout.startAnimation(animationSet);
        mCardNumberIsViewed = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            applyTint(false);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!mInitFlag && getWidth() != 0) {
            mInitFlag = true;
            mTotalLengthInPixels = getFrameWidth();

            updateSpaceSizes(mCardNumberIsViewed);

            int cardLeftMargin = mCardNumberIsViewed
                    ? 0 : -1 * mPlacementParameters.hiddenCardWidth;
            setLayoutValues(mPlacementParameters.cardWidth, cardLeftMargin, mCardNumberEditText);


            int dateMargin = mCardNumberIsViewed
                    ? mPlacementParameters.cardWidth + mPlacementParameters.cardDateSeparation
                    : mPlacementParameters.peekCardWidth + mPlacementParameters.cardDateSeparation;
            setLayoutValues(mPlacementParameters.dateWidth, dateMargin, mExpiryDateEditText);

            int cvcMargin = mCardNumberIsViewed
                    ? mTotalLengthInPixels
                    : mPlacementParameters.peekCardWidth
                    + mPlacementParameters.cardDateSeparation
                    + mPlacementParameters.dateWidth
                    + mPlacementParameters.dateCvcSeparation;
            setLayoutValues(mPlacementParameters.cvcWidth, cvcMargin, mCvcNumberEditText);
        }
    }

    @NonNull
    private String getHiddenTextForBrand(@NonNull @Card.CardBrand String brand) {
        if (Card.AMERICAN_EXPRESS.equals(brand)) {
            return HIDDEN_TEXT_AMEX;
        } else {
            return HIDDEN_TEXT_COMMON;
        }
    }

    @NonNull
    private String getCvcPlaceHolderForBrand(@NonNull @Card.CardBrand String brand) {
        if (Card.AMERICAN_EXPRESS.equals(brand)) {
            return CVC_PLACEHOLDER_AMEX;
        } else {
            return CVC_PLACEHOLDER_COMMON;
        }
    }

    @NonNull
    private String getPeekCardTextForBrand(@NonNull @Card.CardBrand String brand) {
        if (Card.AMERICAN_EXPRESS.equals(brand)) {
            return PEEK_TEXT_AMEX;
        } else if (Card.DINERS_CLUB.equals(brand)) {
            return PEEK_TEXT_DINERS;
        } else {
            return PEEK_TEXT_COMMON;
        }
    }

    private void applyTint(boolean isCvc) {
        if (isCvc || Card.UNKNOWN.equals(mCardNumberEditText.getCardBrand())) {
            Drawable icon = mCardIconImageView.getDrawable();
            Drawable compatIcon = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(compatIcon.mutate(), mTintColorInt);
            mCardIconImageView.setImageDrawable(DrawableCompat.unwrap(compatIcon));
        }
    }

    private void updateCvc(@NonNull @Card.CardBrand String brand) {
        if (Card.AMERICAN_EXPRESS.equals(brand)) {
            mCvcNumberEditText.setFilters(
                    new InputFilter[]{new InputFilter.LengthFilter(CardUtils.CVC_LENGTH_AMEX)});
            mCvcNumberEditText.setHint(R.string.cvc_amex_hint);
        } else {
            mCvcNumberEditText.setFilters(
                    new InputFilter[]{new InputFilter.LengthFilter(CardUtils.CVC_LENGTH_COMMON)});
            mCvcNumberEditText.setHint(R.string.cvc_number_hint);
        }
    }

    private void updateIcon(@NonNull @Card.CardBrand String brand) {
        if (Card.UNKNOWN.equals(brand)) {
            Drawable icon = getResources().getDrawable(R.drawable.ic_unknown);
            mCardIconImageView.setImageDrawable(icon);
            applyTint(false);
        } else {
            mCardIconImageView.setImageResource(BRAND_RESOURCE_MAP.get(brand));
        }
    }

    private void updateIconCvc(@NonNull @Card.CardBrand String brand, boolean isEntering) {
        if (isEntering) {
            if (Card.AMERICAN_EXPRESS.equals(brand)) {
                mCardIconImageView.setImageResource(R.drawable.ic_cvc_amex);
            } else {
                mCardIconImageView.setImageResource(R.drawable.ic_cvc);
            }
            applyTint(true);
        } else {
            updateIcon(brand);
        }
    }

    /**
     * Interface useful for testing calculations without generating real views.
     */
    @VisibleForTesting
    interface DimensionOverrideSettings {
        int getPixelWidth(@NonNull String text, @NonNull EditText editText);

        int getFrameWidth();
    }

    /**
     * Class used to encapsulate the functionality of "backing up" via the delete/backspace key
     * from one text field to the previous. We use this to simulate multiple fields being all part
     * of the same EditText, so a delete key entry from field N+1 deletes the last character in
     * field N. Each BackUpFieldDeleteListener is attached to the N+1 field, from which it gets
     * its {@link #onDeleteEmpty()} call, and given a reference to the N field, upon which
     * it will be acting.
     */
    private class BackUpFieldDeleteListener implements StripeEditText.DeleteEmptyListener {

        private StripeEditText backUpTarget;

        BackUpFieldDeleteListener(StripeEditText backUpTarget) {
            this.backUpTarget = backUpTarget;
        }

        @Override
        public void onDeleteEmpty() {
            String fieldText = backUpTarget.getText().toString();
            if (fieldText.length() > 1) {
                backUpTarget.setText(
                        fieldText.substring(0, fieldText.length() - 1));
            }
            backUpTarget.requestFocus();
            backUpTarget.setSelection(backUpTarget.length());
        }
    }

    /**
     * A data-dump class.
     */
    @VisibleForTesting
    class PlacementParameters {
        int cardWidth;
        int hiddenCardWidth;
        int peekCardWidth;
        int cardDateSeparation;
        int dateWidth;
        int dateCvcSeparation;
        int cvcWidth;

        int cardTouchBufferLimit;
        int dateStartPosition;
        int dateRightTouchBufferLimit;
        int cvcStartPosition;

        @Override
        public String toString() {
            String touchBufferData = String.format("Touch Buffer Data:\n" +
                            "CardTouchBufferLimit = %d\n" +
                            "DateStartPosition = %d\n" +
                            "DateRightTouchBufferLimit = %d\n" +
                            "CvcStartPosition = %d",
                    cardTouchBufferLimit,
                    dateStartPosition,
                    dateRightTouchBufferLimit,
                    cvcStartPosition);
            String elementSizeData = String.format("CardWidth = %d\n" +
                            "HiddenCardWidth = %d\n" +
                            "PeekCardWidth = %d\n" +
                            "CardDateSeparation = %d\n" +
                            "DateWidth = %d\n" +
                            "DateCvcSeparation = %d\n" +
                            "CvcWidth = %d\n",
                    cardWidth,
                    hiddenCardWidth,
                    peekCardWidth,
                    cardDateSeparation,
                    dateWidth,
                    dateCvcSeparation,
                    cvcWidth);
            return elementSizeData + touchBufferData;
        }
    }

    /**
     * A convenience class for when we only want to listen for when an animation ends.
     */
    private abstract class AnimationEndListener implements Animation.AnimationListener {
        @Override
        public void onAnimationStart(Animation animation) {
            // Intentional No-op
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
            // Intentional No-op
        }
    }

}
