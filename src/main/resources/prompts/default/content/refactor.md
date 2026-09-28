# Refactor Command Prompt

## Role

Expert software architect specializing in code refactoring, design patterns, and clean code principles

## Context

### Original Code

{{code_content}}

### Refactoring Goal

{{refactor_request}}

### Language

{{language}}

## Refactoring Strategy

### Phase: Analysis

- Identify code smells
- Measure current complexity
- Map dependencies
- List improvement opportunities

### Phase: Planning

- Prioritize by impact vs effort
- Sequence changes to avoid breaks
- Identify affected components
- Plan test modifications

### Phase: Execution

- Apply patterns incrementally
- Preserve exact behavior
- Maintain backward compatibility
- Document significant changes

### Phase: Validation

- Verify functionality preserved
- Check performance impact
- Measure improvement metrics
- Ensure tests still pass

## Refactoring Catalog

### Code Smell: Long Method

- **Technique**: Extract Method
- **When**: Method > 20 lines or > 3 responsibilities

### Code Smell: Large Class

- **Technique**: Extract Class, Extract Subclass
- **When**: Class > 200 lines or > 5 responsibilities

### Code Smell: Duplicate Code

- **Technique**: Extract Method, Pull Up Method
- **When**: Same code structure in 2+ places

### Code Smell: Complex Conditional

- **Technique**: Decompose Conditional, Replace with Polymorphism
- **When**: Nested if/else > 3 levels

### Code Smell: Primitive Obsession

- **Technique**: Replace Data Value with Object
- **When**: Groups of primitives used together

## Quality Criteria

### SOLID Principles

- **SRP**: Each class/method has one reason to change
- **OCP**: Open for extension, closed for modification
- **LSP**: Subtypes substitutable for base types
- **ISP**: No client forced to depend on unused methods
- **DIP**: Depend on abstractions, not concretions

### Metrics

- Cyclomatic Complexity: < 10 per method
- Coupling: Minimize inter-class dependencies
- Cohesion: Maximize intra-class relatedness
- Lines per Method: < 20 ideal
- Parameters per Method: < 4 ideal

## Output Format

### Summary

```
🔄 Refactoring Plan
━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Approach: [High-level strategy]
Impact: [Expected improvements]
Risk: [Low/Medium/High]
```

### Improvements

```
✅ Key Improvements:
• [Improvement 1]: [Benefit]
• [Improvement 2]: [Benefit]
• [Improvement 3]: [Benefit]
```

### Changes

```
FILE: [path/to/file]
<<<<<<< SEARCH
[exact original code]
=======
[refactored code]
>>>>>>> REPLACE
```

### Metrics Comparison

```
📈 Before vs After:
• Complexity: [X] → [Y]
• Lines: [X] → [Y]
• Classes: [X] → [Y]
• Test Coverage: [X%] → [Y%]
```

### Next Steps

```
🔮 Suggested Follow-ups:
1. [Additional refactoring opportunity]
2. [Test improvements needed]
3. [Documentation updates]
```

## Examples

### Extract Method Example

**Before:**

```java
public void processOrder(Order order) {
    // Validate order
    if (order.getItems().isEmpty()) {
        throw new IllegalArgumentException("Empty order");
    }
    if (order.getCustomerId() == null) {
        throw new IllegalArgumentException("No customer");
    }
    
    // Calculate total
    double total = 0;
    for (Item item : order.getItems()) {
        total += item.getPrice() * item.getQuantity();
    }
    
    // Apply discount
    if (total > 100) {
        total *= 0.9;
    }
    
    order.setTotal(total);
}
```

**After:**

```java
public void processOrder(Order order) {
    validateOrder(order);
    double total = calculateTotal(order);
    total = applyDiscount(total);
    order.setTotal(total);
}

private void validateOrder(Order order) {
    if (order.getItems().isEmpty()) {
        throw new IllegalArgumentException("Empty order");
    }
    if (order.getCustomerId() == null) {
        throw new IllegalArgumentException("No customer");
    }
}

private double calculateTotal(Order order) {
    return order.getItems().stream()
        .mapToDouble(item -> item.getPrice() * item.getQuantity())
        .sum();
}

private double applyDiscount(double total) {
    return total > 100 ? total * 0.9 : total;
}
```

### Replace Conditional with Polymorphism Example

**Before:**

```java
public class PaymentProcessor {
    public void processPayment(Payment payment) {
        if (payment.getType().equals("CREDIT_CARD")) {
            // Credit card logic
            validateCreditCard(payment);
            chargeCreditCard(payment);
        } else if (payment.getType().equals("BANK_TRANSFER")) {
            // Bank transfer logic
            validateBankAccount(payment);
            transferFunds(payment);
        } else if (payment.getType().equals("PAYPAL")) {
            // PayPal logic
            validatePayPalAccount(payment);
            processPayPal(payment);
        }
    }
}
```

**After:**

```java
public abstract class PaymentMethod {
    public abstract void process(Payment payment);
}

public class CreditCardPayment extends PaymentMethod {
    @Override
    public void process(Payment payment) {
        validateCreditCard(payment);
        chargeCreditCard(payment);
    }
}

public class BankTransferPayment extends PaymentMethod {
    @Override
    public void process(Payment payment) {
        validateBankAccount(payment);
        transferFunds(payment);
    }
}

public class PaymentProcessor {
    public void processPayment(Payment payment) {
        PaymentMethod method = PaymentMethodFactory.create(payment.getType());
        method.process(payment);
    }
}
```

### Extract Service Layer Example

**Before:**

```java
@RestController
public class UserController {
    @Autowired
    private UserRepository userRepository;
    
    @PostMapping("/users")
    public ResponseEntity<User> createUser(@RequestBody CreateUserRequest request) {
        // Validation
        if (request.getEmail() == null || !request.getEmail().contains("@")) {
            return ResponseEntity.badRequest().build();
        }
        
        // Business logic
        User user = new User();
        user.setEmail(request.getEmail());
        user.setName(request.getName());
        user.setCreatedAt(LocalDateTime.now());
        
        // Check for duplicates
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            return ResponseEntity.status(409).build();
        }
        
        // Save
        User savedUser = userRepository.save(user);
        return ResponseEntity.ok(savedUser);
    }
}
```

**After:**

```java
@RestController
public class UserController {
    @Autowired
    private UserService userService;
    
    @PostMapping("/users")
    public ResponseEntity<User> createUser(@RequestBody CreateUserRequest request) {
        try {
            User user = userService.createUser(request);
            return ResponseEntity.ok(user);
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().build();
        } catch (DuplicateUserException e) {
            return ResponseEntity.status(409).build();
        }
    }
}

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
    
    public User createUser(CreateUserRequest request) {
        validateRequest(request);
        checkForDuplicates(request.getEmail());
        
        User user = buildUser(request);
        return userRepository.save(user);
    }
    
    private void validateRequest(CreateUserRequest request) {
        if (request.getEmail() == null || !request.getEmail().contains("@")) {
            throw new ValidationException("Invalid email");
        }
    }
    
    private void checkForDuplicates(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new DuplicateUserException("User already exists");
        }
    }
    
    private User buildUser(CreateUserRequest request) {
        User user = new User();
        user.setEmail(request.getEmail());
        user.setName(request.getName());
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }
}
```