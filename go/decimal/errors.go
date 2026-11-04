package decimal

// Error is the failure of the measure packages (decimal, money, units): a code of the measure contract's
// section 6 and a message safe to show a caller. It is a leaf type: the packages import only the
// standard library and each other, so it does not use the use-case package's error. It implements
// httpfw.ServiceError structurally (Status, Code, Message, Details), so a bad value in a request body
// is a 400 with the contract's code, as in Java.
type Error struct {
	code    string
	message string
}

// NewError returns an Error with a contract code ("CURRENCY_UNKNOWN") and a message.
func NewError(code, message string) *Error { return &Error{code: code, message: message} }

// Error returns "VALIDATION CODE: message", the form of a use-case validation error.
func (e *Error) Error() string { return "VALIDATION " + e.code + ": " + e.message }

// Status is 400: every measure failure is a validation error.
func (e *Error) Status() int { return 400 }

// Code returns the contract's error code.
func (e *Error) Code() string { return e.code }

// Message returns the message, safe to show a caller.
func (e *Error) Message() string { return e.message }

// Details returns nothing: a measure error has no extra values.
func (e *Error) Details() map[string]string { return nil }
